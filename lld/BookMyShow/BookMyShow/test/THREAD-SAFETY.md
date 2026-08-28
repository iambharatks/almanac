# BookMyShow — thread safety review

Suite: `test/tsuite/`. Run with `test/run-tests.sh` (plain JDK 21, no dependencies).
`T*` = invariants the design holds today (regression net). `KB*` = reproduced defects;
each prints `BUG` while the defect is present and `FIXED` once it is gone.

Current: **12 passed, 7 defects reproduced, 0 unexpected failures** (stable over repeated runs).

---

## What the design already gets right

- **Seat state is a single `AtomicReference<SeatState>`.** Status + holder + expiry move as one
  word, so there is no window where a seat is HELD by nobody. This is the right primitive.
- **Hold ordering.** `Show.holdSeats` sorts the seat ids before acquiring. Without it, two buyers
  requesting `[A,B]` and `[B,A]` each grab one seat and roll the other back — both fail, forever,
  under load. `T03` runs 200 rounds of exactly that and always gets one winner.
- **Payment happens outside every lock**, with the seats parked in HELD. Correct shape.
- **All-or-nothing holds**, with rollback on the first failure (`T02`, `T08`, `T09`).
- **Ownership checks** on release/confirm — one user cannot touch another's seat (`T10`).
- **`Theatre.addShow` is `synchronized`**, so the overlap check and the `schedule.put` are atomic
  (`T06`: 16 threads on one slot → exactly one show).
- **Strategies are stateless** with final fields; `MockPaymentStrategy` uses `ThreadLocalRandom`.
- `Show.seats` is `Map.copyOf` in a `final` field → safe publication, no structural races.

---

## Defects

### 1. Stale-snapshot CAS loops — three methods spin forever  (KB03, KB04, KB05)

`Seat.releaseHeld`, `Seat.releaseBooked` and `Seat.confirmSeat` all read the state **once, outside**
the retry loop:

```java
SeatState curState = state.get();          // read once
while (true) {
    if (guard fails on curState) return false;
    if (state.compareAndSet(curState, next)) return true;   // can never match again
}
```

Every `SeatState` is a freshly allocated object, so once the seat moves the CAS can *never* match
`curState` again — and the guard, re-checked against that same stale snapshot, keeps passing. The
thread spins at 100% CPU forever. Not a livelock that resolves: a permanent wedge.

Reachable interleavings:
- `releaseHeld` while another buyer steals the just-expired hold (KB03).
- `releaseBooked` racing another rollback of the same seat (KB04).
- `confirmSeat` while the client retries and re-takes its own lapsed hold (KB05) — this one sits on
  the checkout path, *after* the card is charged, so the customer is charged and the request never
  returns.

Fix — move the read inside the loop:

```java
while (true) {
    SeatState curState = state.get();       // re-read every attempt
    if (guard fails) return false;
    if (state.compareAndSet(curState, next)) return true;
}
```

### 2. `Show.confirmSeats` leaks the seats after the failing one  (KB01)

```java
for (Seat seat : acquiredSeat) {
    if (seat.confirmSeat(userId, now)) bookedSeats.add(seat);
    else { rollback(bookedSeats, userId, Seat::releaseBooked); return Optional.empty(); }
}
```

Only the seats already BOOKED are rolled back. Every seat *after* the failure is still HELD by this
user and is never released — `Venue.bookSeats` does not release them either. They stay unsellable
for the full 60 s TTL. Book 3 seats, fail on the 2nd → seat 3 is dead for a minute.

Fix — also drop the holds that were never confirmed:

```java
} else {
    rollback(bookedSeats, userId, Seat::releaseBooked);
    rollback(acquiredSeat.subList(bookedSeats.size(), acquiredSeat.size()), userId, Seat::releaseHeld);
    return Optional.empty();
}
```

### 3. `now` is caller-supplied, so hold expiry is decided by the client  (KB02)

`Venue.bookSeats(showId, seatIds, userId, now)` threads the caller's `now` into
`Seat.isAvailable(now)`. Pass `now + 1 hour` and **every live hold in the show looks expired** — you
take seats another user is holding and has already paid for. The same parameter also inflates your
own hold: `expiry = now + TTL`.

The class is already inconsistent about it — `bookSeats` uses the caller's `now` to hold but
`System.currentTimeMillis()` to release and confirm.

Fix: take the time from one authoritative source inside the service (`System.currentTimeMillis()`,
or an injected `Clock` so tests can still control it). Never from the request.

### 4. The two ends of the TTL disagree by one millisecond  (KB06)

`isAvailable` treats the hold as expired at `now >= expiry`; `confirmSeat` rejects only at
`expiry < now`. At exactly `now == expiry` a seat is simultaneously stealable *and* confirmable, so a
steal and a confirm are both legitimately in flight. The CAS still serialises them, so nobody
double-books — but it is the boundary that KB05 rides in on. Make both use the same comparison.

### 5. `Show.releaseSeats` NPEs on an unknown seat id  (KB07)

```java
List<Seat> acquired = seatIds.stream().map(seats::get).toList();   // nulls allowed through
rollback(acquired, userId, Seat::releaseHeld);                     // NPE
```

Not reachable from `bookSeats` today (holds validate the ids first), but `releaseSeats` is public and
is the natural entry point for a cancel/expiry path. Filter nulls.

---

## Caveats — where thread safety falls next

**Logging inside the CAS retry loops.** `Seat` has 7 `System.out.println` calls, several of them
*between* a `state.get()` and its CAS. `PrintStream.println` is `synchronized` on the single shared
`System.out`, so every seat operation in the process serialises on one global lock — the booking path
is effectively single-threaded under load, and each race window is stretched by orders of magnitude.
KB03/KB04 exploit exactly that print to hit their interleaving deterministically. Route this to a
logger and drop it out of the hot loops.

**`tryHold`'s guard and its CAS check different reads.**

```java
SeatState cur = state.get();
if (!this.isAvailable(now)) return false;   // isAvailable() does its own state.get()
if (state.compareAndSet(cur, heldState)) return true;
```

Not a live bug: the CAS compares by reference and every `SeatState` is freshly allocated, so a
successful CAS proves the state never moved. But the decision is made on a different read than the one
being swapped, and it survives only by that accident — cache a singleton `SeatState.available()` as an
"optimisation" and the reasoning collapses into classic ABA. Make it `isAvailable(cur, now)`.

**No hold reaper.** Expired holds are only reclaimed opportunistically, when someone happens to try
that seat. It mostly works because `getAvailableSeats` counts expired holds as free — but combined with
defect 2 there is no path that ever cleans up a leaked hold early.

**`Theatre.schedule` is a bare `TreeMap` guarded only by `addShow`'s intrinsic lock.** There is no
reader today, so it is safe. The moment someone adds `getShows()` / `listShowsFor(date)` without
`synchronized`, it is a data race on a `TreeMap` — which can spin or return garbage, not just stale
data. Guard the map, or make it a `ConcurrentSkipListMap`.

**`Venue.addShow` is not atomic end to end.** `theatre.addShow(...)` is synchronized; the following
`shows.put(showId, ...)` is not. Between them the show exists in the theatre's schedule but is
invisible to `getSeatsForShow`. Nobody can observe it today (the id is not published until the method
returns), but it stops being true the moment show ids become predictable or a listing reads the
schedule directly.

**`Venue`'s fields are package-private and `theatres` is a mutable `HashMap`** (`Collectors.toMap`).
Safe today — final field, written once, never mutated — but nothing enforces that. `Map.copyOf` and
`private` cost nothing.

**`activeTickets` is write-only and unbounded.** It is a `ConcurrentHashMap`, so it is safe, but
nothing ever reads or removes from it, and `Ticket` carries no `userId` — so there is no cancel path
and no way to answer "which tickets are mine?". Any future cancel API lands straight on defects 1 and 2.

**No idempotency on payment.** `bookSeats` charges before confirming, with no request key. A client
retry — the exact scenario in KB05 — charges twice. The refund on confirm failure is fire-and-forget:
`refund()` returns void, is never retried, and is skipped entirely if `confirmSeats` throws.

**`ShowBookingSystem`'s static `LinkedHashMap showLabels` and static `Scanner`** are single-threaded
CLI state. Fine for the CLI, unsafe the moment this is fronted by anything concurrent.

**Non-concurrency issues noticed in passing.**
- `ShowBasedPriceStrategy.calculate` returns `basePrice` on the default branch — dropping the seat
  count and the show multiplier. Off-peak weekday bookings are charged for one seat regardless of
  quantity. This is on the money path.
- `TimeSlot.overlaps` uses `isBefore` / `isAfter`, so touching slots (`end == next.start`) count as
  overlapping and back-to-back shows are rejected.
- `Show`'s two constructors take `(showName, showId, ...)` and `(showId, showName, ...)`. Currently
  wired correctly, but the delegation is one edit away from silently swapping them.
- `BookingStrategy` (`SeatSelectionStrategy`, `AutoSeatSelection`, `CustomSeatSelection`) is dead code —
  nothing in `Venue` or `ShowBookingSystem` references it.
