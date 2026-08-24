# LLD Case Studies — Parking Lot & Rate Limiter

> Build notes for revision. Structured as: what was designed, what broke, why it
> broke, and what transfers. The bug logs are the valuable part — they're the
> mistakes actually made, not a list of things that could theoretically go wrong.

---

## Table of Contents

**Part A — Parking Lot**
1. [Problem & Requirements](#a1-problem--requirements)
2. [Final Structure](#a2-final-structure)
3. [Design Decisions](#a3-design-decisions)
4. [Bug Log](#a4-bug-log)
5. [The Race Condition — Full Walkthrough](#a5-the-race-condition--full-walkthrough)
6. [Interview Talking Points](#a6-interview-talking-points)

**Part B — Rate Limiter**
7. [Problem & Requirements](#b1-problem--requirements)
8. [Algorithm Comparison](#b2-algorithm-comparison)
9. [Final Structure](#b3-final-structure)
10. [Design Decisions](#b4-design-decisions)
11. [Bug Log](#b5-bug-log)
12. [Test Results & What They Prove](#b6-test-results--what-they-prove)
13. [Interview Talking Points](#b7-interview-talking-points)
14. [Known Gaps](#b8-known-gaps-state-these-dont-build-them)

**Part C — Cross-Cutting**
14. [Patterns That Repeated Across Both](#c1-patterns-that-repeated-across-both)
15. [Mistake Taxonomy](#c2-mistake-taxonomy)

---

# PART A — PARKING LOT

## A1. Problem & Requirements

Design a multi-floor parking lot.

**Functional**
- Multiple floors, multiple spot types (small / medium / large)
- `park(vehicle)` → issues a Ticket; `unpark(ticket)` → computes fee, frees the spot
- Pluggable spot-allocation policy (nearest-first, etc.)
- Pluggable pricing policy (size-based, flat-rate, etc.)
- Query free spots by floor and vehicle type

**Non-functional**
- Thread-safe: many entry gates park concurrently
- In-memory, single JVM

**Scope trap:** Parking Lot has no natural boundary — you can spend six hours on it.
Things people add that earn zero marks: payment gateways, entry/exit gate hardware,
ticket persistence, reservation systems, display boards. If you're 40 minutes in and
building a `DisplayBoard`, the round is lost.

---

## A2. Final Structure

```
ParkingLot          singleton; holds floors + activeTickets; owns the lock
ParkingFloor        Map<String, ParkingSpot>
ParkingSpot         ENTITY: spotId, spotSize, isOccupied, vehicle
Ticket              ENTITY: ticketId, spot, vehicle, entryTime, exitTime
Vehicle             licensePlate, VehicleSize
ParkingSpotAllocationStrategy   interface → NearestFirstStrategy
FeeCalculationStrategy          interface → SizeBasedFeeCalculation
```

**Core flow:**

```java
public Optional<Ticket> parkVehicle(Vehicle vehicle) {
    synchronized (this) {                                     // find AND claim
        Optional<ParkingSpot> spot = parkingStrategy.findParkingSpot(floors, vehicle);
        if (spot.isPresent() && spot.get().parkVehicle(vehicle)) {
            Ticket ticket = new Ticket(spot.get(), vehicle);
            activeTickets.put(vehicle.getLicensePlate(), ticket);
            return Optional.of(ticket);
        }
    }
    return Optional.empty();
}

public Optional<Double> unparkVehicle(String licenseNumber) {
    Ticket ticket;
    synchronized (this) {                                     // SAME lock
        ticket = activeTickets.remove(licenseNumber);         // atomic — one winner
        if (ticket == null) return Optional.of(PENALTY);
        ticket.setExitTime(LocalDateTime.now());
        ticket.getParkingSpot().unparkVehicle(ticket.getVehicle());
    }
    return Optional.of(feeStrategy.calculateFee(ticket));      // OUTSIDE the lock
}
```

---

## A3. Design Decisions

### Two strategies on orthogonal axes

Allocation cares about spot size and availability. Pricing cares about vehicle type
and duration. Neither influences the other, so a change to one never touches the
other. This is the same orthogonality as (cloud provider × rollout strategy) in the
Abstract Factory example.

### Pricing strategy stored on the Ticket, not read from the lot at exit

```java
Ticket ticket = new Ticket(spot, vehicle, feePolicy.strategyFor(vehicle));
// at exit:
double fee = ticket.getFeeStrategy().calculateFee(ticket);
```

**Two wins:**

1. **The rate quoted at entry is honoured at exit.** If rates change mid-park, the
   ticket carries the terms it was issued under. That's how parking, hotels, and
   insurance actually work.
2. **The race disappears** — not because you locked it, but because the shared mutable
   field is no longer on the read path.

**Store the strategy object, not a name.** A name forces a lookup, and a lookup is a
`switch`, which is the `if/else` OCP violation Strategy exists to remove:

```java
// WRONG — reintroduces the branching
if (name.equals("HOURLY")) return hourly.calc(...);
else if (name.equals("FLAT")) return flat.calc(...);
```

**Caveat worth stating:** if you persisted tickets, a strategy object can't be
serialized into a DB row — you'd store an identifier and rehydrate through a registry.
So the name-based approach isn't wrong in general, it's wrong *here* because
everything is in memory. Saying that shows you know why the in-memory version is a
simplification.

### `PricingPolicy` selects; `PricingStrategy` computes

Two responsibilities, two objects. Merging them is the common mistake.

### Subscription belongs to the customer, not the vehicle

Considered classifying vehicles as subscription vs daily. Wrong — subscription is a
property of the *customer*. Same car, different driver, different terms. Extending
`Vehicle` into `SubscribedCar` breaks the moment a subscription lapses, because you
can't change an object's class at runtime.

```java
Ticket park(Vehicle v, Customer c) {
    return new Ticket(..., policy.strategyFor(v, c));    // pass as context
}
```

### Configuration vs operation, split by interface

Problem: `addFloor()` (structural setup, called once) and `park()` (runtime, called
constantly) both lived on `ParkingLot`, so every caller could restructure the lot.

**Rejected:** an `Admin` class. That's role-based thinking — roles are an
*authorization* concern, and an `Admin` class would just be `ParkingLot` with
different method names, inviting `AdminService` / `UserService` / `ManagerService`,
a hierarchy modelling your org chart instead of your domain.

**Chosen:** two interfaces on one object.

```java
interface ParkingOperations     { Ticket park(Vehicle v); Money unpark(Ticket t); }
interface ParkingAdministration { void addFloor(Floor f); void closeFloor(int id); }
final class ParkingLot implements ParkingOperations, ParkingAdministration { }
```

A caller holding `ParkingOperations` **cannot** add a floor — the method isn't on the
type. Compiler-enforced, not convention.

**Even better if floors are fixed at startup:** build the structure in a Builder, and
`addFloor` doesn't exist on the lot at all. Only spot occupancy changes at runtime.

### The Singleton — and why it caused problems

Used the holder idiom (correct implementation). But it forced a cascade:

- A private no-arg constructor **cannot accept dependencies**
- → strategies had to be hardcoded in the constructor (DIP violation) or set via
  mutable setters
- → mutable setters reintroduced a race on `feeStrategy`
- → fixing that needed a two-phase `configure()`-then-`getInstance()` workaround

**The lesson: the singleton wasn't a design choice, it was a constraint that generated
three subsequent problems.**

**Interview line:**
> "I made it a singleton for the demo, but a registry holding multiple lots would be
> better — global mutable state is hard to test and the 'one instance' claim breaks
> across multiple JVMs anyway."

---

## A4. Bug Log

| # | Bug | Symptom | Root cause | Fix |
|---|---|---|---|---|
| 1 | `private static Class HOLDER{` | won't compile | `Class` is a **type**, not the `class` keyword | `private static class Holder` |
| 2 | `activeTickets` never initialized | NPE on first park | declared, never assigned | init in constructor |
| 3 | `isOccupied` was `Boolean`, never set | NPE unboxing `null` | wrapper type + no initializer | `private boolean isOccupied = false` |
| 4 | Missing `ConcurrentHashMap` import | won't compile | — | add import |
| 5 | `final` fields + setters still present | won't compile | half-refactor | delete the setters |
| 6 | `ticket` declared inside `synchronized`, used after | won't compile | block scoping | declare outside, assign inside |
| 7 | `canFitVehicle` returned nothing | won't compile | switch computed values, discarded them | `return !isOccupied && spotSize == v.getVehicleSize()` |
| 8 | `unpark` used `get()` not `remove()` | ticket never cleared → that plate can never park again; two threads both free the spot | non-atomic read | `activeTickets.remove(plate)` |
| 9 | `unpark` had no lock | double-free, double-charge, and unpark racing with park | — | same lock as `park` |
| 10 | `park` and `unpark` used **different** locks | zero mutual exclusion despite both looking synchronized | `ParkingLot.class` vs none | one lock object in both |
| 11 | `synchronized(ParkingLot.class)` | public monitor; also one lock across all instances | wrong lock object | `this`, or a private `final Object lock` |
| 12 | **find + claim not atomic** | **30 cars parked in one spot** | check-then-act | one `synchronized` block around both |
| 13 | `Duration.toHours()` truncates | every fee was `0.0` | integer truncation | `Math.max(1, ceil(minutes / 60.0))` |
| 14 | `System.out` in domain methods | untestable; drowned test output; I/O serialized threads and *hid* races | presentation in domain layer | return values, print in `main()` |
| 15 | `@Data` on `ParkingSpot` | generated `setOccupied()` — anyone can bypass the lock; `equals` on all fields is wrong for an entity | Lombok aggregate annotation | `@Getter` only, or hand-write |
| 16 | Lombok build failure | `package lombok does not exist` | annotation processing disabled | enable it — or drop Lombok entirely |

### The ones worth re-reading

**#8 — `get()` vs `remove()`.** This single change fixed *two* bugs at once: the ticket
leak, and a check-then-act in unpark (two threads both `get` the same ticket, both
free the spot). `remove()` returns the value and clears the entry atomically, so
exactly one thread gets a non-null ticket.

**#10 and #11 — the lock object.** Both methods *looked* synchronized. Neither excluded
the other, because `synchronized(X)` only creates mutual exclusion between threads
naming **the same X**. This is the single most-missed thing about `synchronized`.

**#15 — `@Data`.** Same failure as the value/entity confusion: it generates setters
(bypassing invariants) and `equals` over all fields (wrong for an entity, where
identity is the ID). Prefer domain methods (`parkVehicle()`) over raw setters — the
method name carries meaning that `setOccupied(true)` doesn't.

---

## A5. The Race Condition — Full Walkthrough

The most valuable thing in this whole exercise.

### The setup

`ParkingSpot.parkVehicle` had its own guard:

```java
public boolean parkVehicle(Vehicle vehicle) {
    if (isOccupied) return false;        // ← check
    this.vehicle = vehicle;
    this.isOccupied = true;              // ← act
    return true;
}
```

### First run — WITHOUT `synchronized` in `ParkingLot.parkVehicle`

```
=== TEST 1: no double allocation ===
tickets issued: 5 (expected 5)
PASS: 5 distinct spots, no double allocation

=== TEST 2: no spot leak after churn ===
spots reclaimed: 5 / 5
PASS: every spot released correctly
```

**Both tests passed with the lock removed.** 25 of 30 threads printed "Some error
occurred" — those were the inner `if (isOccupied)` correctly rejecting them.

**This is the dangerous state:** the code *looks* correct, the test *says* correct,
and it is not correct.

### Why it passed

The race window is between `if (isOccupied)` and `isOccupied = true` — roughly **three
bytecodes, a couple of nanoseconds.** For a collision, two threads must both execute
the read before either executes the write. Thread-start jitter is measured in
microseconds. You'd run this thousands of times before seeing a failure.

The inner check wasn't a guard. It was a **narrow window**.

### Second run — widen the window artificially

```java
public boolean parkVehicle(Vehicle vehicle) {
    if (isOccupied) return false;
    try { Thread.sleep(100); } catch (InterruptedException e) {}   // ← widen
    this.vehicle = vehicle;
    this.isOccupied = true;
    return true;
}
```

```
CAR-8  has been parked at M-5.
CAR-29 has been parked at M-5.
CAR-18 has been parked at M-5.
CAR-14 has been parked at M-5.
... all 30 at M-5

=== TEST 1: no double allocation ===
tickets issued: 30 (expected 5)
FAIL: expected 5 winners, got 30
```

**Thirty cars, five spots, every single one at M-5.** 100% reproducible.

All 30 threads called `findParkingSpot`, all got M-5 (the first free spot), all
reached the `if` while `isOccupied` was still `false`, all passed, all set it to
`true`, all got a ticket.

### The lessons

> **1. A passing concurrency test proves nothing.** Same code, two runs, PASS then
> FAIL. Nothing about the code was safe — the timing just hid it.

> **2. A narrow race is still a race.** It fires under load, on different hardware,
> with a different core count. In production, at 2am.

> **3. To verify a fix, keep the sleep in place.** Restore `synchronized`, run *with*
> the widened window, and confirm exactly 5 on 5 distinct spots. That proves the lock
> **closes** the window rather than merely narrowing it.

> **4. Visibility was also broken.** `isOccupied` wasn't `volatile`, so even setting
> atomicity aside, one thread's write might not be visible to another. `synchronized`
> fixed atomicity *and* visibility together — which is why it was the right tool
> rather than `volatile` alone.

### Test design that made it detectable

```java
final int THREADS = 30, SPOTS = 5;       // tiny resource pool, large thread count

CountDownLatch startGate = new CountDownLatch(1);   // fire simultaneously
// each worker: startGate.await();
startGate.countDown();

CountDownLatch done = new CountDownLatch(THREADS);  // assert only after all finish
done.await();

Set<String> used = new HashSet<>();                 // THE ASSERTION
for (Ticket t : issued)
    if (!used.add(t.getParkingSpot().getSpotId())) duplicates.add(...);
assert issued.size() == SPOTS && duplicates.isEmpty();
```

Without the **start gate**, thread 1 finishes before thread 30 is created — they never
overlap and the race can't fire. Without the **assertion**, the test can't fail.

**Second test shape — churn:** 20 threads × 50 rounds of park-then-unpark, then a
black-box check that all 5 spots are reclaimable. Catches leaks and double-frees that
the contention test misses.

---

## A6. Interview Talking Points

**On the lock granularity limitation (say this unprompted):**
> "This serializes every park and unpark across the whole lot, including cars on
> different floors that can't conflict. I'd shard the lock per floor, or make the spot
> claim a CAS so threads only contend when they pick the same spot."

**If asked to actually do per-floor locking** — note the restructure it requires. The
strategy currently searches *across* floors, so by the time you know which floor to
lock, the search is done and the spot can be taken. You have to push the search inside
the floor's lock:

```java
for (ParkingFloor floor : strategy.floorOrder(floors, vehicle)) {
    Optional<Ticket> t = floor.tryPark(vehicle);   // lock lives INSIDE
    if (t.isPresent()) return t;
}
```

The strategy's role changes from "pick the spot" to "pick the floor order" — arguably
cleaner separation: policy decides where to look, the floor owns its own state.

*Never hold two floor locks at once* — if you add "move vehicle between floors",
acquire in ascending floor order or you have a deadlock.

**On why inner methods aren't synchronized:**
> "`ParkingSpot.parkVehicle` isn't synchronized because every caller already holds the
> lot's lock. Adding it there would be a second uncontended lock, and worse, it would
> advertise that the method is safe to call standalone — which it isn't."

**On the singleton:**
> "Made it a singleton for the demo. In production I'd inject the lot — there can be
> multiple lots, and global state is hard to test."

---

# PART B — RATE LIMITER

## B1. Problem & Requirements

Design an in-memory rate limiter for an API gateway. The gateway calls it before
forwarding each request and needs one decision: allow or reject.

**Functional**
1. Limits enforced **per resource** (user ID, API key, IP). One client exhausting its
   quota must not affect another.
2. N requests per time window.
3. Support more than one algorithm, selectable at startup. Implement at least two.
4. After a window elapses, the client can request again.

**Non-functional**
5. Thread-safe — many gateway threads, same and different resources.
6. Low latency; different resources must not block each other.
7. In-memory, single JVM.

### Clarifying questions and their answers

| Question | Answer | Why |
|---|---|---|
| Handle all resource types? | No — an opaque `String` key | Whether it's a user ID or an IP is the caller's concern. A `RateLimitTarget` hierarchy is scope creep. |
| Rejected requests — queue, retry, drop? | **Drop.** Return `false`, gateway returns 429 | Backoff is the client's responsibility. Queueing turns a limiter into a scheduler. |
| Is the resource key space bounded? | **Assume yes** for the exercise | See below — this is the memory question. |
| Window aligned to wall clock, or rolling from first request? | **Aligned** | Removes a stored field and a race. See below. |

### The unbounded key space problem

Per-resource state lives in a map. Every new key creates an entry.

- Keys are **user IDs from a fixed customer base** → a few thousand entries, fine.
- Keys are **IPs from the open internet** → an attacker sends one request each from a
  million spoofed IPs and the map grows to a million entries, never removed.

**The rate limiter meant to protect you becomes the memory exhaustion vector.**

**How real systems handle it:**

| Approach | Mechanism | Trade-off |
|---|---|---|
| **TTL eviction** (most common) | Redis `SETEX` — key expires on its own; in-process, Caffeine `expireAfterAccess` | None really — this is why production limiters live in Redis |
| **Bounded cache + LRU** | cap at N entries, evict LRU | An evicted attacker gets a fresh quota — acceptable; survival beats perfect enforcement |
| **Coarser keys** | limit by /24 subnet, or require an API key | Key space is bounded by construction |
| **Probabilistic counting** | Count-Min Sketch — fixed memory, small overcount | Occasionally limits someone slightly early |

> **The pattern: either bound the memory (eviction), bound the key space (coarser
> keys), or accept approximation. Exact per-key state over an unbounded key space is
> not a thing you can have.**

**Also worth knowing:** with N app servers each holding local state, a client hitting a
limit of 10 gets 10 *per server* — effectively 10N. Rate limiting is inherently a
shared-state problem, which is the real reason production systems centralize it in
Redis. Same "who owns the truth?" question, one layer up.

### Window alignment

**Aligned to wall clock:** every window is the same for everyone.
`windowId = currentTimeMillis() / windowSizeMillis`. No stored start time at all.

**Rolling from first request:** each client's window starts when it first arrives.
Fairer per client, spreads resets out — but you must store a `windowStart` per
resource and keep it consistent with the counter, which is compound state.

**Chose aligned**, and this is a real design win rather than a shortcut:

> **Compound state became single-value state because of a modelling decision.** With
> the window folded into the key, there is no reset operation at all — a new window is
> simply a new key with a fresh counter at zero. The reset race was *designed away*,
> not locked away.

Downside: every client's counter resets at the same instant, so you can get a
thundering herd at each tick, and the boundary burst is more synchronized.

---

## B2. Algorithm Comparison

| Algorithm | State per key | Boundary burst? | Bursts allowed? | Complexity |
|---|---|---|---|---|
| **Fixed window** | counter + window id | Yes — up to 2× at the seam | No | Trivial |
| **Sliding window log** | timestamp per request | No — exact | No | Easy, memory-heavy |
| **Sliding window counter** | 2 counters | Mostly — approximated away | No | Medium |
| **Token bucket** | tokens + last refill | No | **Yes, deliberately** | Easy |
| **Leaky bucket** | queue + drain rate | No | No — smooths output | Medium |

**Chose fixed window + token bucket.** Reasoning:

1. **Maximally different.** Fixed window counts *arrivals in a period*; token bucket
   models *a refilling allowance*. That contrast makes the Strategy interface earn its
   place. Fixed window + sliding window log would be two variations on "count recent
   requests" — less interesting, and an interviewer notices.
2. **Token bucket is what real systems use** (AWS API Gateway, Stripe). It's the only
   one that *intentionally permits bursts* — an idle client accumulates tokens and can
   spend them at once. That's a design property, not a bug.
3. **They exercise different concurrency shapes** — single value vs compound state.

**Why not the others:**
- *Sliding window log* — most accurate, least practical. One timestamp per request
  means memory scales with traffic, which is exactly the unbounded-growth problem.
- *Sliding window counter* — what production uses when it wants fixed-window cost with
  less boundary burst (weights the previous window by how far into the current one you
  are). Good to name; the arithmetic eats 20 minutes.
- *Leaky bucket* — genuinely different: a **queue** that drains at constant rate, so
  it *shapes* traffic rather than rejecting. Contradicts the "drop rejected requests"
  requirement and needs a drain thread.

---

## B3. Final Structure

```
RateLimiter             holds the strategy, delegates isAllowed()
RateLimiterStrategy     interface: boolean isAllowed(String resourceId)
FixedWindowStrategy     Map<String, AtomicLong> + windowSizeMillis, limit + sweeper
TokenBucketStrategy     Map<String, Bucket>     + capacity, refillRate
Bucket                  final class: tokens (double), lastRefillMillis, synchronized tryConsume()
```

Five types. **Nothing else** — no `RateLimitConfig`, no `Request`, no
`RateLimitResult`, no `WindowManager`.

### Fixed window

```java
public boolean isAllowed(String resourceId) {
    long windowId = System.currentTimeMillis() / windowSizeMillis;
    String key = resourceId + ":" + windowId;              // window baked into the key

    AtomicLong counter = counters.computeIfAbsent(key, k -> new AtomicLong(0));

    while (true) {
        long current = counter.get();
        if (current >= limit) return false;
        if (counter.compareAndSet(current, current + 1)) return true;
        // lost the race — re-read and retry
    }
}
```

### Token bucket

```java
// TokenBucketStrategy
public boolean isAllowed(String resourceId) {
    Bucket bucket = buckets.computeIfAbsent(resourceId, k -> new Bucket(capacity));
    return bucket.tryConsume(System.currentTimeMillis(), refillRate, capacity);
}

// Bucket — lock lives WITH the data it guards
synchronized boolean tryConsume(long nowMillis, double ratePerSec, double capacity) {
    double elapsedSec = Math.max(0, nowMillis - lastRefillMillis) / 1000.0;
    tokens = Math.min(capacity, tokens + elapsedSec * ratePerSec);   // MIN, not MAX
    lastRefillMillis = nowMillis;
    if (tokens < 1) return false;
    tokens -= 1;
    return true;
}
```

### Eviction (fixed window)

```java
sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "ratelimit-sweeper");
    t.setDaemon(true);                              // don't block JVM shutdown
    return t;
});
sweeper.scheduleAtFixedRate(this::evictStale, windowMillis, windowMillis, MILLISECONDS);

private void evictStale() {
    long currentWindow = System.currentTimeMillis() / windowMillis;
    counters.keySet().removeIf(key -> parseWindowId(key) < currentWindow);
}
```

**Why it's safe:** `ConcurrentHashMap`'s iterator is weakly consistent — no
`ConcurrentModificationException`, tolerates concurrent writes. And a key from a past
window can never be written again, since `windowId` only increases. No race between
the sweeper and `isAllowed`.

---

## B4. Design Decisions

### Each strategy owns its own map — `RateLimiter` holds none

Fixed window needs a counter. Token bucket needs tokens + last-refill-time. **Different
shapes.** If `RateLimiter` held `Map<String, ?>` it would have to know which shape,
which couples it to the algorithm and destroys the swap.

So `RateLimiter` becomes almost trivial:

```java
public final class RateLimiter {
    private final RateLimiterStrategy strategy;
    public boolean isAllowed(String id) { return strategy.isAllowed(id); }
}
```

That's correct, not a problem. It's the injection point and the public API; the
algorithm and its state travel together inside the strategy.

### Refill rate is config, not per-resource state

Initially modelled as `resource -> (tokens, refillRate)`. Wrong — refill rate is the
same for every resource and never changes. It's a `final` field on the strategy.
Per-resource state is **only** what actually varies: tokens and last refill time.

> **Config flows down from the top and is `final`. Only mutable state goes in the map.**

### Strategy is fixed at construction — and that's a property, not a limitation

Switching algorithms at runtime would orphan the per-resource state: a resource with 7
tokens has no fixed-window equivalent. You'd either discard all state (every client
gets a fresh quota — exploitable) or attempt a translation that's incoherent.

**Interview line:**
> "The strategy is fixed at construction. Switching at runtime would orphan the
> per-resource state, and different algorithms' state isn't translatable — so a swap
> would either reset everyone's quota or be meaningless."

### No singleton (unlike Parking Lot)

A real gateway rate-limits **per endpoint** — 100/sec on `/search`, 5/sec on `/login`,
1000/sec on `/health`. Different limits, different instances. Multi-instance is the
*normal* case here, not an edge case. Plus: constructor injection just works, and
tests get a fresh limiter per scenario.

This is the direct lesson from Parking Lot, applied.

### Two different concurrency mechanisms, deliberately

| Strategy | State shape | Mechanism | Why |
|---|---|---|---|
| Fixed window | single value (a counter) | `AtomicLong` + CAS loop | Locking around a single-variable read-modify-write is exactly what atomics exist to avoid |
| Token bucket | compound (tokens + timestamp) | `synchronized` on the Bucket | Two fields must change together |

**This split is itself the answer** to "why did you choose differently in each?" —
better than using one tool everywhere.

**Note the lock is already fine-grained:** it's on the individual `Bucket`, so two
clients never contend. Contention only occurs for concurrent requests from the *same*
client, which is exactly when serialization is wanted. And an uncontended
`synchronized` stays a **thin lock** — a CAS on the mark word, tens of nanoseconds.

**The alternative for compound state (worth knowing):** CAS an immutable snapshot.

```java
record Bucket(double tokens, long lastRefillMillis) {}    // immutable
AtomicReference<Bucket> ref = ...;

while (true) {
    Bucket current = ref.get();
    Bucket next = computeNext(current, now());            // no mutation
    if (next.tokens() < 1) return false;
    if (ref.compareAndSet(current, next)) return true;    // both fields swap atomically
}
```

**The trap:** `AtomicReference<MutableBucket>` gives you nothing. CAS compares
*references* — mutate in place and the reference never changes, so the CAS always
"succeeds" while another thread mutates underneath. **The object must be immutable.**

### Why not `incrementAndGet()`

```java
if (counter.incrementAndGet() <= limit) return true;   // BUG
return false;
```

Rejected requests still increment. Under sustained overload the counter climbs to
millions, and you can't distinguish "at limit" from "way past limit". The CAS loop
only increments when it's actually going to allow.

### `now` is passed in, not read inside `Bucket`

```java
bucket.tryConsume(System.currentTimeMillis(), refillRate, capacity);   // injected
```

**Why:** testability. With `now` as a parameter you can drive the clock directly —
`tryConsume(0, ...)` then `tryConsume(1000, ...)` tests the refill instantly and
deterministically. If `Bucket` called `System.currentTimeMillis()` internally, every
refill test would need a real `Thread.sleep`, and time-dependent tests are flaky by
nature.

> **The clock is a dependency.** Same rule as the pricing strategy: a class shouldn't
> construct its own dependencies.

**The one exception is the constructor** — `new Bucket(capacity)` should set
`lastRefillMillis = now` itself. Passing `0` creates bug #7 (a fresh bucket appears to
have been idle since 1970, so it can be swept before its first use).

**The formalized version, worth naming but not building:** production code injects
`java.time.Clock`, and tests supply `Clock.fixed(...)`. Same idea; not worth the
minutes here.

### Rejected: factory, builder, and config object

Three "should I add a pattern here?" questions came up. All three answers were no, and
the reasoning generalizes.

**Factory —** it would contain a `switch` on an algorithm string:

```java
return switch (type) {
    case "TOKEN_BUCKET" -> new TokenBucketStrategy(capacity, rate);
    case "FIXED_WINDOW" -> new FixedWindowStrategy(window, limit);
};
```

That's the exact `if/else`-on-a-type-field smell Strategy exists to remove, and every
new algorithm means editing it. Meanwhile `new RateLimiter(new TokenBucketStrategy(10, 2))`
is already one line, explicit, and type-safe.

> **Add a factory when construction is non-trivial, or when the choice comes from
> *data* rather than code.** If `main()` can just write `new X(...)`, a factory is
> ceremony.

**Config object —** feels "more abstract", is actually less:

```java
class RateLimitConfig {
    String algorithm;
    long capacity;        // token bucket only
    double refillRate;    // token bucket only
    long windowMillis;    // fixed window only
    int limit;            // fixed window only
}
```

Every field is junk for one of the two algorithms. `RateLimitConfig(algorithm="FIXED_WINDOW",
refillRate=2.5)` compiles fine and means nothing, so you need runtime validation to
reject nonsense. **You've replaced a type-checked API with a bag of optional fields
plus a validator.** That's ISP violated in config form.

"Abstract" means *hiding how it works* — a typed strategy already does that (the caller
never sees the CAS loop, the bucket, or the sweeper). A stringly-typed config hides
nothing extra; it just moves errors from compile time to runtime.

**Builder —** the interesting one, because the first attempt was *wrong*:

```java
RateLimiter.builder().tokenBucket(10, 2.0).build();     // looks reasonable
RateLimiter.builder().fixedWindow(...).tokenBucket(...) // ...silently overwrites
```

Those two methods are **mutually exclusive alternatives**, not accumulating settings.
Calling both silently overwrites — which is precisely the "invalid state is
expressible" problem the builder was supposed to solve. It doesn't solve it; it just
moves the failure.

> **Builder is for several parameters, many of them optional, where telescoping
> constructors would multiply. Not for a single required choice — that's a constructor
> argument.**

A builder would fit if the limiter accumulated genuinely independent settings — an
eviction interval, a max-tracked-resources cap, a metrics collector, alongside the
required strategy. That's real accumulation. (Bucket4j's builder accumulates
*bandwidths* — you can add several limits to one bucket — which is the same shape.)

**Where the config string legitimately lives:** at the boundary. Spring binds YAML →
`RateLimitProperties`, and one adapter class turns that into the right strategy. String
dispatch is unavoidable there, but it stays in a single class at the edge, and the
domain remains typed.

### Millis vs nanos

- `System.currentTimeMillis()` for the **window ID** — that's a wall-clock-aligned
  question.
- For the **bucket refill**, `nanoTime()` is technically more correct (monotonic;
  immune to NTP jumps), but millis is fine at this resolution and keeps both strategies
  consistent. Guard with `Math.max(0, now - lastRefill)` so a backwards clock jump
  can't produce a negative elapsed.

---

## B5. Bug Log

| # | Bug | Symptom | Root cause | Fix |
|---|---|---|---|---|
| 1 | `compareAndSet(current, current + windowSize)` | with windowSize=1000, first request jumps counter to 1000 → everything rejected | typo: incremented by window size | `current + 1` |
| 2 | Key separator `'-'` collides with UUIDs | `parseLong` throws on some IDs | UUIDs are full of hyphens; `lastIndexOf('-')` is fragile | use `':'`, or better a `record WindowKey(String, long)` |
| 3 | Both eviction strategies present | extra map write on every request in the hot path | lazy `remove(prev)` **and** the scheduled sweeper | keep the sweeper, delete the lazy line |
| 4 | `windowSize = 1` | **nothing was ever rejected** | 1 **millisecond** window — loops take longer, so every request lands in a new window | use 1000; and rename the field `windowSizeMillis` |
| 5 | Token bucket had **no refill at all** | worked as a one-time quota that never came back | `refillTime` set to 0, never used, never updated | compute elapsed × rate inside `tryConsume` |
| 6 | `Math.max(capacity, refilled)` | **20/20 requests allowed with capacity 5** | `max` takes the *larger*, so over-refill wins and tokens grow unbounded | `Math.min(capacity, ...)` |
| 7 | `new Bucket(capacity, 0)` | first refill computes ~1.7 billion seconds elapsed | `lastRefillMillis` initialized to 0 (the epoch) | let `Bucket`'s constructor set it to `now` |
| 8 | `tokens` as `long` (avoided) | bucket would never refill at fractional rates | 2 tokens/sec over 100ms = 0.2, truncates to 0 | `double` |
| 9 | Argument order risk | silent misbehaviour | `tryConsume(now, capacity, rate)` vs `(now, rate, capacity)` — both `double`, compiler can't catch a swap | verify at the call site; consider distinct types |
| 10 | Test used 10 distinct UUIDs | limit never engaged | each resource gets its own counter — testing 10 independent limiters of 1 request each | **one** resource ID |
| 11 | `System.out.println` in the strategy | drowns test output; I/O serializes threads and *hides* races | presentation in domain logic | return the boolean, print in `main()` |
| 12 | Demo had no assertions | can't fail, so tells you nothing | printed results, judged by eye | count with `AtomicInteger`, assert exact numbers |
| 13 | Test asserted `LIMIT = 10` against `new TokenBucketStrategy(5, 2)` | `FAIL: expected 10, got 5` — but the limiter was **correct** | hardcoded expectation didn't match the test's own setup | derive the expectation from the config: `final int CAPACITY = 10;` used in both places |
| 14 | Token bucket had **no eviction** while fixed window did | unbounded map growth; asymmetry between the two strategies | eviction was only implemented on one path | idle-based sweeper: remove buckets where `now - lastRefill > idleThreshold` |
| 15 | `counter.get(k)` inside `removeIf` | possible NPE in the sweeper | weakly consistent iterator — the entry can vanish between iteration and lookup | iterate `entrySet()`; use `e.getValue()`, no second lookup |
| 16 | `getLastRefillMillis()` read outside any lock | sweeper may see a stale timestamp (bucket survives one extra cycle) | `tryConsume` writes it inside `synchronized`; the read has no happens-before edge | move the decision into `Bucket`: `synchronized boolean isIdle(long now, long threshold)` |
| 17 | Two near-identical constructors | duplicated init, guaranteed to drift | copy-paste instead of delegation | `this(capacity, refillRate, 1000)` |
| 18 | `THRESHOLD` as an instance field in caps | misleading — caps is the `static final` convention; also no unit in the name | naming | `idleThresholdMillis` |
| 19 | Stray `;;` after the sweeper field | harmless, but noise | typo | — |

### The ones worth re-reading

**#4 and #10 together — the test-parameter class of bug.** Twice, the limiter appeared
to allow everything, and both times the *code* was fine and the *parameters* made the
limit unreachable. A 1ms window means no two requests share a window. Ten UUIDs mean
no two requests share a counter. A refill rate of 10,000/sec means the bucket refills
faster than you can drain it.

> **When a limiter "allows everything", check your parameters before your code.** The
> failing condition has to be *reachable* for the test to mean anything.

**#6 — `max` vs `min`.** One character, and it silently removed the entire capacity
cap. The output (20 consecutive `true` with capacity 5) was the giveaway: not just
"too many" but "no cap at any point".

**#5 — the missing refill.** Without it, this wasn't a token bucket at all; it was a
one-time quota. Worth noting the diagnostic: the code *looked* complete and compiled
fine. The field `refillTime` existed, was assigned in the constructor, and was never
read — a dead field is a strong signal something is unimplemented.

**#13 — the test was wrong, not the code.** The output read `FAIL: expected 10, got 5`
— and 5 was the *correct* answer, because capacity was 5. The test declared
`LIMIT = 10` as a separate constant from the strategy's configured capacity, and the
two drifted.

> **When a test fails, check that its expectation was derived from its own setup
> before you go looking in the implementation.** Two independent constants that must
> agree is a bug waiting to happen — derive one from the other.

The same run *did* prove the real thing: exactly 5 allowed under 100-way contention
means the `synchronized` on the bucket held.

**#14 — the asymmetry.** Fixed window had eviction; token bucket didn't. Worth noticing
as a class of mistake: **when two implementations of the same interface differ in what
non-functional work they do, one of them is probably incomplete.** An interviewer will
spot the asymmetry immediately.

Token bucket's eviction is also genuinely harder. A fixed-window entry is *provably*
dead once its window passes (window IDs only increase). A bucket is only dead if the
client is idle — which requires tracking last-access and evicting on inactivity. That
is real TTL eviction, and it's the same mechanism an LRU-with-TTL cache needs.

**#15 and #16 — the sweeper has its own concurrency requirements.** Easy to treat
background cleanup as "not the real code" and skip the analysis. It isn't: it reads
shared state concurrently with the hot path, so it needs the same reasoning. #15 is a
check-then-act in disguise (iterate, then look up — the entry can vanish in between),
and #16 is a plain visibility gap.

> **Any code that touches shared state gets the same scrutiny, including cleanup,
> metrics, and logging paths.**

---

## B6. Test Results & What They Prove

### Single-threaded behaviour test

```java
var strategy = new TokenBucketStrategy(5, 2);   // capacity 5, 2 tokens/sec
String id = "user-1";                            // ONE resource

for (int i = 0; i < 10; i++) limiter.isAllowed(id);   // phase 1
Thread.sleep(1000);
for (int i = 0; i < 10; i++) limiter.isAllowed(id);   // phase 2
```

```
0: true   1: true   2: true   3: true   4: true      ← capacity 5, drained
5: false  6: false  7: false  8: false  9: false     ← cap working

--- sleep 1000ms ---

0: true   1: true                                     ← 1 sec × 2/sec = 2 tokens
2: false  3: false  ... 9: false                      ← refill working, correctly bounded
```

**What each phase proves:**

- **Phase 1** — the capacity cap binds. Catches bug #6 (`max` vs `min`).
- **Phase 2** — the refill computes correctly *and* is bounded. Catches bug #5
  (no refill → 0 allowed) **and** bug #8 (`long` truncation → also 0 allowed).

Phase 2 is the one that actually distinguishes a token bucket from a quota.

### Concurrency test (the shape to use)

```
ONE resource. Limit 10. 100 threads. CountDownLatch start gate.
→ assert exactly 10 allowed, 90 rejected

Sleep past the window. Fire 100 more.
→ assert exactly 10 more allowed          (catches a broken window transition)

2 resources × 50 threads each, limit 10.
→ assert 10 allowed per resource          (catches per-key isolation bugs)
```

That third scenario matters: if state were accidentally shared across keys, the first
two would still pass.

**Run it against both strategies** — they exercise two different mechanisms
(`synchronized` on the bucket vs the CAS loop on the counter).

**To see the CAS earn its place:** replace it with `incrementAndGet()` plus a plain
`if`, and watch more than 10 get through.

### The swap demo

Same `main()`, change only the strategy passed to `RateLimiter`. If nothing else needs
touching, the Strategy interface has earned its place — **that's OCP demonstrated
rather than claimed.**

---

## B7. Interview Talking Points

**On algorithm choice:**
> "I implemented fixed window and token bucket. Fixed window is simplest but allows a
> 2× burst at the boundary. Token bucket avoids that and deliberately permits bursts up
> to bucket capacity, which is why most cloud APIs use it. Sliding window log is exact
> but stores a timestamp per request, so memory scales with traffic."

**On the memory leak (say this unprompted):**
> "I'm keeping per-resource state in a map with no bound on distinct clients. With a
> bounded key space that's fine; with open-internet IPs it's an unbounded cache and I'd
> need TTL-based eviction of idle entries — Redis `SETEX`, or Caffeine
> `expireAfterAccess` in-process."

**On the two concurrency mechanisms:**
> "I used a CAS loop where the state is a single value, and a lock where it's compound
> state that has to change together. The lock is per-bucket so different clients never
> contend."

**On window alignment:**
> "Fixed window aligned to wall-clock boundaries — the window ID is just
> `now / windowSize`, so there's no stored start time to keep consistent with the
> counter. The reset race disappears because a new window is just a new key."

**On distributed rate limiting (the natural follow-up):**
> "With multiple app servers each holding local state, a limit of 10 becomes 10 per
> server. Rate limiting is inherently shared-state, so production systems put the
> counters in Redis — which also gives you TTL expiry for free."

**On why no factory / builder / config object:**
> "The algorithms don't share a parameter shape, so a generic config object would mix
> fields that don't apply and push errors to runtime. A factory would just be a switch
> on a type string, which is the branching Strategy removes. For library ergonomics I'd
> expose a builder — but only once there are several optional settings to accumulate."

**On testability:**
> "`now` is a parameter rather than read inside the bucket, so refill behaviour is
> testable without sleeping. In production I'd inject a `java.time.Clock`."

---

## B8. Known Gaps (state these; don't build them)

Things deliberately left out. Naming a limitation unprompted scores better than
silently having it.

| Gap | What's missing | What to say |
|---|---|---|
| **Lifecycle** | The sweeper thread runs for the JVM's lifetime; no `shutdown()` | "`RateLimiter` should implement `AutoCloseable` and shut the executor down — otherwise every instance leaks a thread. Daemon threads make it survivable, not correct." |
| **Rejection carries no information** | `false` says nothing about *when* to retry | "Real limiters return `Retry-After`. Token bucket can compute it exactly: `(1 - tokens) / refillRate` seconds." |
| **Unbounded client cardinality** | Eviction is time-based; nothing caps total distinct resources | "With open-internet IPs I'd need an LRU bound as well as TTL, or coarser keys." |
| **Clock** | `currentTimeMillis` is wall-clock; an NTP correction backwards yields negative elapsed | "`nanoTime` is monotonic and correct for elapsed-time measurement. Guarded with `Math.max(0, ...)` in the meantime." |
| **Per-resource limits** | Every resource shares one limit | "Real gateways configure per endpoint — `/login` at 5/sec, `/search` at 100/sec. That's the case that would justify a config object." |
| **Boundary burst** | Fixed window allows 2× at the seam | "Sliding window counter fixes it by weighting the previous window; I skipped it because the arithmetic doesn't teach anything new here." |

### A note on test noise

In the contention test, a few `true` results appear scattered late among the
rejections. That's the refill working correctly — 100 threads take a few milliseconds
to drain through, and at 2 tokens/sec a fraction of a token accumulates during the run.

For an exact assertion, either set the refill rate to 0 (pure capacity check) or assert
`allowed <= capacity + 1`. **Worth understanding rather than "fixing"** — it's the
system behaving correctly, and misreading it as a bug would send you looking in the
wrong place.

---

# PART C — CROSS-CUTTING

## C1. Patterns That Repeated Across Both

### Check-then-act appeared four times

| Where | Check | Act | Symptom |
|---|---|---|---|
| Parking spot | `findParkingSpot()` | `spot.parkVehicle()` | 30 cars, one spot |
| Parking unpark | `activeTickets.get()` | free the spot | double-free |
| Rate limiter | `counter.get()` | increment | more than N allowed |
| Seat booking (discussed) | `containsKey(seat)` | `put(seat, user)` | two users, one seat |

**Same bug. Same three fixes:** lock across both steps; make the claim atomic
(`compareAndSet` / `putIfAbsent` / `remove`); or make acquisition itself the claim
(pool checkout).

### "Thread-safe container ≠ thread-safe operation" appeared twice

- `ConcurrentHashMap` didn't protect the `AtomicLong` inside it
- `ConcurrentHashMap` didn't protect the `Ticket` retrieved from it

The container guarantees its *own* operations are atomic. It has no idea what you do
with the value afterward.

### Config injection appeared in both

Parking Lot: rates hardcoded in the constructor → DIP violation → forced by the
singleton's no-arg constructor.
Rate Limiter: refill rate correctly a `final` field, injected.

> **A class owns the algorithm; the caller owns the values.** `main()` builds the
> config → into the strategy → the strategy holds it `final`.

### Printing from domain methods appeared in both

Both times it: made the class untestable, drowned test output, and — most importantly
— **serialized threads via I/O, which hides races.**

### The value/entity distinction drove both

- Parking Lot: `Spot` and `Ticket` are entities (identity + mutable) → classes, not
  records; `@Data`'s all-fields `equals` is wrong for them
- Rate Limiter: `Bucket` is per-resource mutable state → a `final class` with the lock
  on it, not a record

---

## C2. Mistake Taxonomy

Sorting every bug from both problems by *kind* — this is the useful view, because it
tells you what to check next time.

### 1. Uninitialized / wrong-typed state (5 occurrences)

`activeTickets` null · `isOccupied` as `Boolean` null · `lastRefillMillis = 0` ·
`tokens` as `long` · `refillTime` never used

**Check:** every field assigned in the constructor; primitives over wrappers unless
null means something; a field that's never read is a signal something is unimplemented.

### 2. Lock errors (4 occurrences)

Two methods, different lock objects · lock on `ParkingLot.class` (public + static) ·
missing lock entirely on unpark · over-locking inner methods

**Check:** do all methods touching this invariant name the **same** lock object? Is the
lock private? Is it at the level of the invariant, not scattered?

### 3. Check-then-act (4 occurrences)

Covered above.

**Check:** any place you read shared state and then write based on what you read.

### 4. Test parameters and expectations (4 occurrences)

1ms window · 10,000 tokens/sec · 10 distinct UUIDs · `LIMIT = 10` asserted against a
capacity of 5

**Two sub-kinds, and they fail in opposite directions:**

- *Failure unreachable* — the first three. The limit never binds, so everything passes
  and the test proves nothing.
- *Expectation drifted from setup* — the fourth. The test fails while the code is
  correct, sending you to debug the wrong file.

**Check:** before concluding anything from a test result, verify (a) the failing
condition is reachable with these parameters, and (b) the expectation was derived from
the setup rather than typed independently.

### 5. Arithmetic (3 occurrences)

`Math.max` instead of `min` · `+ windowSize` instead of `+ 1` · `toHours()` truncation

**Check:** off-by-a-word errors in bounded arithmetic. These compile, run, and produce
plausible-looking output.

### 6. Presentation in the domain layer (3 occurrences)

`System.out` in `parkVehicle`, in `isAllowed`, and in the sweeper.

### 7. Background/cleanup code treated as second-class (2 occurrences)

`counter.get(k)` inside the sweeper's `removeIf` (NPE race) · `getLastRefillMillis()`
read with no lock (visibility gap)

**Check:** cleanup, metrics, and logging paths touch shared state too. They get the
same concurrency analysis as the hot path.

### 8. Pattern applied without checking it fits (3 near-misses, all avoided)

Factory that would reintroduce a type switch · config object that trades type safety
for a validator · builder for mutually exclusive alternatives

**Check:** name the specific problem the pattern solves, then confirm you have that
problem. "It feels more abstract" and "libraries do this" aren't reasons.

### 9. Tooling (3 occurrences)

Lombok annotation processing disabled · `@Data` generating unwanted setters · jar
attached but not assigned to the module

**Lesson:** for a 5-class in-memory exercise with a 90-minute clock, Lombok's saving
(a handful of accessors) is smaller than its setup and failure cost. Records cover the
value-object case natively.

---

## Appendix: Reusable Test Template

```java
public class ConcurrencyTest {
    public static void main(String[] args) throws Exception {
        final int THREADS = 100, LIMIT = 10;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(THREADS);
        ExecutorService pool     = Executors.newFixedThreadPool(THREADS);
        AtomicInteger allowed    = new AtomicInteger();
        AtomicInteger rejected   = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();                       // fire together
                    if (subject.isAllowed("user-1")) allowed.incrementAndGet();
                    else rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        done.await();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println(allowed.get() == LIMIT
            ? "PASS: exactly " + LIMIT + " allowed"
            : "FAIL: expected " + LIMIT + ", got " + allowed.get());
    }
}
```

**The four required elements:**
1. **Start gate** — without it threads don't overlap and no race fires
2. **Completion gate** — without it you assert while threads are still running
3. **Small resource pool, many threads** — maximises contention
4. **An assertion that can fail** — without it the test is a demo

**And:** run it ~20 times. A single green run means little. To confirm a fix is real,
widen the race window with a `sleep`, verify it FAILS without the fix, then verify it
PASSES with the fix and the sleep still in place.