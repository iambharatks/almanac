package tsuite;

import Entities.Show;
import Entities.Ticket;
import Entities.Venue;
import Entities.Seat.Seat;
import Entities.Seat.SeatState;
import Entities.Seat.SeatStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static tsuite.Harness.*;

/**
 * Thread-safety suite for the BookMyShow LLD.
 *
 * T-tests assert behaviour the design already gets right - they are the regression net.
 * KB-tests reproduce real defects. "BUG" in the output means the defect is still present;
 * "FIXED" means the defect is gone and the test should be retired.
 *
 * Run:  test/run-tests.sh
 */
public final class ThreadSafetyTests {

    private static final int SEATS = 8;

    public static void main(String[] args) {
        say("");
        say("BookMyShow - thread safety suite");
        say("=".repeat(96));
        say("-- invariants the design should hold --");

        /* ================================================================= T01 */
        test("T01", "one seat, 32 concurrent buyers -> exactly one ticket", () -> {
            Fixtures.OkPay pay = new Fixtures.OkPay(5);
            Venue venue = Fixtures.venueWithSeats(pay, SEATS);
            Show show = Fixtures.newShow(venue, "SpiderMan");
            long now = System.currentTimeMillis();

            List<Ticket> tickets = race(32, i ->
                    venue.bookSeats(show.getShowId(), List.of("A00"), "user-" + i, now).orElse(null));

            checkEq(tickets.size(), 1, "double booking: more than one buyer got seat A00");
            Seat a00 = seatsOf(show).get("A00");
            checkEq(statusOf(a00), SeatStatus.BOOKED, "winning seat is not BOOKED");
            checkEq(ticketsOf(show).size(), 1, "activeTickets disagrees with the returned tickets");
            checkEq(pay.refunds.get(), 0, "a refund was issued even though nothing failed to confirm");
        });

        /* ================================================================= T02 */
        test("T02", "overlapping multi-seat requests are all-or-nothing", () -> {
            Fixtures.OkPay pay = new Fixtures.OkPay(2);
            Venue venue = Fixtures.venueWithSeats(pay, SEATS);
            Show show = Fixtures.newShow(venue, "Odyssey");
            long now = System.currentTimeMillis();

            List<Ticket> tickets = race(24, i ->
                    venue.bookSeats(show.getShowId(), Fixtures.randomSeats(SEATS, 2), "user-" + i, now)
                         .orElse(null));

            Map<String, Seat> seats = seatsOf(show);
            Set<String> seen = new HashSet<>();
            for (Ticket t : tickets) {
                checkEq(t.bookedSeats().size(), 2, "ticket " + t.ticketId() + " is a partial booking");
                for (String id : t.bookedSeats()) {
                    check(seen.add(id), "seat " + id + " was sold on two different tickets");
                    checkEq(statusOf(seats.get(id)), SeatStatus.BOOKED,
                            "seat " + id + " is on a ticket but is not BOOKED");
                }
            }
            assertNoSeatLeftHeld(show);
        });

        /* ================================================================= T03 */
        test("T03", "opposite request orders never abort each other (hold ordering)", () -> {
            for (int round = 0; round < 200; round++) {
                Venue venue = Fixtures.venue(new Fixtures.OkPay(), "A00", "A01");
                Show show = Fixtures.newShow(venue, "Troy");
                long now = System.currentTimeMillis();
                List<List<String>> orders = List.of(List.of("A00", "A01"), List.of("A01", "A00"));

                List<Ticket> tickets = race(2, i ->
                        venue.bookSeats(show.getShowId(), orders.get(i), "user-" + i, now).orElse(null));

                checkEq(tickets.size(), 1, "round " + round
                        + ": both requests aborted - Show.holdSeats stopped sorting seat ids, so two "
                        + "buyers grabbed one seat each and then rolled each other back");
            }
        });

        /* ================================================================= T04 */
        test("T04", "declined payment returns every held seat to the pool", () -> {
            Fixtures.FailPay pay = new Fixtures.FailPay(2);
            Venue venue = Fixtures.venueWithSeats(pay, SEATS);
            Show show = Fixtures.newShow(venue, "Good Will Hunting");
            long now = System.currentTimeMillis();

            List<Ticket> tickets = race(16, i ->
                    venue.bookSeats(show.getShowId(), Fixtures.randomSeats(SEATS, 3), "user-" + i, now)
                         .orElse(null));

            checkEq(tickets.size(), 0, "a ticket was issued despite every payment being declined");
            for (Map.Entry<String, Seat> e : seatsOf(show).entrySet()) {
                checkEq(statusOf(e.getValue()), SeatStatus.AVAILABLE,
                        "seat " + e.getKey() + " was not released after the payment failed. " + dump(show));
            }
            checkEq(venue.getSeatsForShow(show.getShowId()).orElseThrow().size(), SEATS,
                    "inventory shrank after a run of failed payments");
        });

        /* ================================================================= T05 */
        test("T05", "mixed stress: ledger stays consistent while readers poll", () -> {
            Fixtures.FlakyPay pay = new Fixtures.FlakyPay(0.35, 3);
            Venue venue = Fixtures.venueWithSeats(pay, SEATS);
            Show show = Fixtures.newShow(venue, "Interstellar");
            String showId = show.getShowId();
            long now = System.currentTimeMillis();

            final int bookers = 24, readers = 6;
            CountDownLatch done = new CountDownLatch(bookers);
            List<Ticket> tickets = new ArrayList<>(race(bookers + readers, i -> {
                if (i >= bookers) {                       // reader: must never blow up or see a sold seat free
                    while (done.getCount() > 0) {
                        venue.getSeatsForShow(showId).orElseThrow();
                    }
                    return null;
                }
                try {
                    return venue.bookSeats(showId, Fixtures.randomSeats(SEATS, 2), "user-" + i, now)
                                .orElse(null);
                } finally {
                    done.countDown();
                }
            }));

            Set<String> onTickets = new HashSet<>();
            for (Ticket t : tickets) {
                for (String id : t.bookedSeats()) {
                    check(onTickets.add(id), "seat " + id + " sold twice");
                }
            }
            assertNoSeatLeftHeld(show);

            Set<String> booked = new HashSet<>();
            seatsOf(show).forEach((id, s) -> { if (statusOf(s) == SeatStatus.BOOKED) booked.add(id); });
            checkEq(booked, onTickets, "the BOOKED seats and the seats on issued tickets disagree");

            List<String> free = venue.getSeatsForShow(showId).orElseThrow();
            for (String id : free) check(!booked.contains(id), "sold seat " + id + " is still advertised as free");
            checkEq(free.size() + booked.size(), SEATS, "seats vanished from the inventory");
            checkEq(tickets.size(), pay.charges.get(), "successful charges and issued tickets disagree");
            checkEq(pay.refunds.get(), 0, "money was refunded even though no confirm should have failed");
        });

        /* ================================================================= T06 */
        test("T06", "concurrent addShow on one overlapping slot -> exactly one wins", () -> {
            Venue venue = Fixtures.venueWithSeats(new Fixtures.OkPay(), 4);
            var slot = Fixtures.freshSlot();

            List<Show> added = race(16, i -> venue.addShow(Fixtures.THEATRE, "clash-" + i, slot).orElse(null));

            checkEq(added.size(), 1, "Theatre.addShow let overlapping shows into the schedule");
            checkEq(showsOf(venue).size(), 1, "Venue.shows registered a show the theatre rejected");
        });

        /* ================================================================= T07 */
        test("T07", "concurrent addShow on disjoint slots -> all are registered", () -> {
            Venue venue = Fixtures.venueWithSeats(new Fixtures.OkPay(), 4);

            List<Show> added = race(16, i -> venue.addShow(Fixtures.THEATRE, "show-" + i, Fixtures.freshSlot())
                                                  .orElse(null));

            checkEq(added.size(), 16, "a non-overlapping show was wrongly rejected");
            checkEq(showsOf(venue).size(), 16, "Venue.shows lost an entry (shows.put runs outside the lock)");
            for (Show s : added) {
                check(venue.getSeatsForShow(s.getShowId()).isPresent(), "show " + s.getShowId() + " is unreachable");
            }
        });

        /* ================================================================= T08 */
        test("T08", "duplicate seat ids in one request fail cleanly", () -> {
            Venue venue = Fixtures.venueWithSeats(new Fixtures.OkPay(), 4);
            Show show = Fixtures.newShow(venue, "dupes");
            long now = System.currentTimeMillis();

            Optional<Ticket> t = venue.bookSeats(show.getShowId(), List.of("A00", "A00"), "u", now);

            check(t.isEmpty(), "a request naming the same seat twice was accepted");
            checkEq(statusOf(seatsOf(show).get("A00")), SeatStatus.AVAILABLE,
                    "the seat stayed HELD after the duplicate request was rejected");
        });

        /* ================================================================= T09 */
        test("T09", "an unknown seat id rolls the whole request back", () -> {
            Venue venue = Fixtures.venueWithSeats(new Fixtures.OkPay(), 4);
            Show show = Fixtures.newShow(venue, "ghost-seat");
            long now = System.currentTimeMillis();

            Optional<Ticket> t = venue.bookSeats(show.getShowId(), List.of("A00", "ZZZ"), "u", now);

            check(t.isEmpty(), "a request naming a non-existent seat was accepted");
            checkEq(statusOf(seatsOf(show).get("A00")), SeatStatus.AVAILABLE,
                    "the valid seat stayed HELD after the request was rejected");
        });

        /* ================================================================= T10 */
        test("T10", "one user cannot release or confirm another user's seat", () -> {
            Seat seat = new Seat("A00");
            long t = 1_000_000L;
            check(seat.tryHold("userA", t, 60_000), "setup: userA could not hold the seat");

            check(!seat.tryHold("userB", t, 60_000), "userB stole a live hold");
            check(!seat.releaseHeld("userB"), "userB released userA's hold");
            check(!seat.confirmSeat("userB", t), "userB confirmed userA's hold");
            check(seat.confirmSeat("userA", t), "userA could not confirm their own hold");
            check(!seat.releaseBooked("userB"), "userB un-booked userA's seat");
            checkEq(statusOf(seat), SeatStatus.BOOKED, "seat did not stay BOOKED for userA");
        });

        /* ================================================================= T11 */
        test("T11", "an expired hold is stolen by exactly one of 16 contenders", () -> {
            Seat seat = new Seat("A00");
            long t = 1_000_000L;
            check(seat.tryHold("owner", t, 0), "setup: hold failed");   // expires at t

            List<String> winners = race(16, i -> seat.tryHold("user-" + i, t, 60_000) ? "user-" + i : null);

            checkEq(winners.size(), 1, "an expired hold was handed to more than one buyer");
            checkEq(holderOf(seat), winners.get(0), "the seat is held by someone who did not win the CAS");
        });


        /* ================================================================= T12 */
        test("T12", "a Ticket exposes immutable seat ids, not live Seat objects", () -> {
            Venue venue = Fixtures.venueWithSeats(new Fixtures.OkPay(), 4);
            Show show = Fixtures.newShow(venue, "ticket");
            Ticket t = venue.bookSeats(show.getShowId(), List.of("A00"), "u", System.currentTimeMillis())
                            .orElseThrow();

            checkEq(t.bookedSeats(), List.of("A00"), "the ticket does not name the seat that was booked");
            try {
                t.bookedSeats().add("GHOST");
                throw new Harness.Failed("Ticket.bookedSeats() is mutable - a holder can rewrite the ticket");
            } catch (UnsupportedOperationException expected) {
                // good: Show.confirmSeats hands out an immutable List.of-style list
            }
            checkEq(ticketsOf(show).get(t.ticketId()), t, "the ticket is not registered in activeTickets");
        });

        say("");
        say("-- defects --");

        /* ================================================================ KB01 */
        knownBug("KB01", "confirmSeats leaks the seats after the one that fails", () -> {
            Fixtures.GatedPay pay = new Fixtures.GatedPay("userA");
            Venue venue = Fixtures.venue(pay, "A1", "A2", "A3");
            Show show = Fixtures.newShow(venue, "leak");
            String showId = show.getShowId();
            long now = System.currentTimeMillis();

            AtomicReference<Optional<Ticket>> result = new AtomicReference<>(Optional.empty());
            Thread buyer = daemon("userA", () ->
                    result.set(venue.bookSeats(showId, List.of("A1", "A2", "A3"), "userA", now)));
            buyer.start();
            await(pay.entered);                       // A1..A3 are HELD by userA, payment in flight

            // Anything that drops the middle hold works here: an expiring TTL, a retry, a cancel.
            show.releaseSeats(List.of("A2"), "userA", System.currentTimeMillis());
            pay.release.countDown();
            buyer.join(10_000);
            check(!buyer.isAlive(), "the booking thread never finished");

            check(result.get().isEmpty(), "a ticket was issued for a seat the user no longer held");
            checkEq(pay.refunds.get(), 1, "the charge was not refunded after the confirm failed");
            Map<String, Seat> seats = seatsOf(show);
            checkEq(statusOf(seats.get("A1")), SeatStatus.AVAILABLE, "A1 was not rolled back from BOOKED");
            checkEq(statusOf(seats.get("A3")), SeatStatus.AVAILABLE,
                    "A3 is still HELD by userA. Show.confirmSeats only rolls back the seats it already "
                    + "BOOKED; every seat after the failing one keeps its hold for the full 60s TTL, and "
                    + "Venue.bookSeats does not release them either. dump: " + dump(show));
        });

        /* ================================================================ KB02 */
        knownBug("KB02", "a caller-supplied `now` lets anyone steal live holds", () -> {
            Fixtures.GatedPay pay = new Fixtures.GatedPay("userA");
            Venue venue = Fixtures.venue(pay, "A1", "A2");
            Show show = Fixtures.newShow(venue, "clock");
            String showId = show.getShowId();
            long now = System.currentTimeMillis();

            AtomicReference<Optional<Ticket>> aResult = new AtomicReference<>(Optional.empty());
            Thread userA = daemon("userA", () ->
                    aResult.set(venue.bookSeats(showId, List.of("A1", "A2"), "userA", now)));
            userA.start();
            await(pay.entered);                       // A1, A2 HELD by userA with a 60s TTL

            // userB simply claims it is an hour later; every live hold now looks expired to tryHold().
            Optional<Ticket> bResult = venue.bookSeats(showId, List.of("A1", "A2"), "userB", now + 3_600_000L);

            pay.release.countDown();
            userA.join(10_000);

            check(bResult.isEmpty(),
                    "userB booked seats that userA was holding, purely by passing a future `now`. "
                    + "`now` is untrusted input threaded from Venue.bookSeats into Seat.isAvailable, so "
                    + "hold expiry is decided by the caller. userA's result=" + aResult.get()
                    + ", refunds=" + pay.refunds.get());
        });

        /* ================================================================ KB06 */
        knownBug("KB06", "at now == expiry a hold is both stealable and confirmable", () -> {
            Seat seat = new Seat("A00");
            long t = 1_000_000L;
            check(seat.tryHold("userA", t, 5_000), "setup: hold failed");
            long expiry = stateOf(seat).expiryMillis();

            boolean stealable = seat.isAvailable(expiry);          // now >= expiry  -> true
            check(!stealable,
                    "isAvailable() calls the seat free at now == expiry while confirmSeat() still accepts "
                    + "it (its guard is `expiry < now`). The two ends of the TTL disagree by one "
                    + "millisecond, so a steal and a confirm can legitimately be in flight together.");
        });

        /* ================================================================ KB07 */
        knownBug("KB07", "Show.releaseSeats NPEs on an unknown seat id", () -> {
            Venue venue = Fixtures.venueWithSeats(new Fixtures.OkPay(), 4);
            Show show = Fixtures.newShow(venue, "npe");
            try {
                show.releaseSeats(List.of("A00", "NOPE"), "u", System.currentTimeMillis());
            } catch (NullPointerException e) {
                throw new Harness.Failed("Show.releaseSeats maps unknown ids to null and passes null "
                        + "straight into Seat::releaseHeld -> " + e);
            }
        });

        say("");
        say("-- defects that hang a thread (run last: they park a spinning thread until repaired) --");

        /* ================================================================ KB03 */
        knownBug("KB03", "Seat.releaseHeld spins forever if the seat changes hands mid-call", () -> {
            Seat seat = new Seat("A00");
            long t = 1_000_000L;
            check(seat.tryHold("userA", t, 0), "setup: hold failed");   // already expired at t
            SeatState heldByA = stateOf(seat);

            Thread releaser = daemon("releaser", () -> seat.releaseHeld("userA"));
            // releaseHeld() prints between its single state.get() and its CAS loop.
            // Use that window to run a genuine second thread that steals the expired hold.
            HOOK.arm("Trying to release seat", releaser, () -> {
                Thread thief = daemon("thief", () -> seat.tryHold("userB", t, 60_000));
                thief.start();
                try { thief.join(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            hooked();
            releaser.start();
            releaser.join(2_000);
            boolean hung = releaser.isAlive();
            if (hung) {                                            // repair so the thread can exit
                stateRef(seat).set(heldByA);
                releaser.join(5_000);
            }
            quiet();

            check(HOOK.didFire(), "the hook never fired - releaseHeld no longer prints, retarget this test");
            check(!hung,
                    "releaseHeld() reads curState once, *outside* the retry loop, then spins on "
                    + "compareAndSet(curState, ...). Every SeatState is a fresh object, so once the seat "
                    + "moves the CAS can never succeed again and the guard - re-checked against the same "
                    + "stale snapshot - never lets the thread out. 100% CPU, forever.");
        });

        /* ================================================================ KB04 */
        knownBug("KB04", "Seat.releaseBooked spins forever on a concurrent double release", () -> {
            Seat seat = new Seat("A00");
            long t = 1_000_000L;
            check(seat.tryHold("userA", t, 60_000) && seat.confirmSeat("userA", t), "setup failed");
            SeatState bookedByA = stateOf(seat);

            Thread first = daemon("unbook-1", () -> seat.releaseBooked("userA"));
            HOOK.arm("Trying to unbook seat", first, () -> {
                Thread second = daemon("unbook-2", () -> seat.releaseBooked("userA"));
                second.start();
                try { second.join(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            hooked();
            first.start();
            first.join(2_000);
            boolean hung = first.isAlive();
            if (hung) {
                stateRef(seat).set(bookedByA);
                first.join(5_000);
            }
            quiet();

            check(HOOK.didFire(), "the hook never fired - releaseBooked no longer prints, retarget this test");
            check(!hung,
                    "same stale-snapshot loop as releaseHeld. Two rollback paths racing on one seat - "
                    + "confirmSeats' rollback and a retry, say - wedge one of them permanently.");
        });

        /* ================================================================ KB05 */
        knownBug("KB05", "Seat.confirmSeat spins forever if the hold is re-taken mid-call", () -> {
            // The scenario: userA's hold lapses while the payment gateway is slow, and their client
            // retries (a double-click, a gateway retry). Each retry re-takes the seat for the *same*
            // user, so confirmSeat's guard keeps passing - but the SeatState object it snapshotted
            // before the retry is gone, and its CAS can never match again.
            //
            // Two mechanics make that deterministic without touching production code:
            //  * a long user id: the guard runs userId.equals(curState.heldby()), which on an 8 MB
            //    non-identical string takes ~100us, so the read-to-CAS window is far wider than the
            //    retry period. The race is identical with a short id, it is just won far more rarely.
            //  * the retries are replayed straight into the AtomicReference from a pool of pre-built
            //    snapshots carrying exactly the fields tryHold(userA, t, 0) installs - never the same
            //    object twice, or the CAS would match again by luck. Holding those references is what
            //    lets the test hand the wedged thread its snapshot back at the end instead of leaving
            //    a core spinning for the rest of the run.
            String userA = "u".repeat(8_000_000);
            String userACopy = new String(userA.toCharArray());
            Seat seat = new Seat("A00");
            long t = 1_000_000L;
            check(seat.tryHold(userA, t, 0), "setup: hold failed");        // HELD by userA, expiry == t

            AtomicReference<SeatState> ref = stateRef(seat);
            SeatState s0 = ref.get();
            final int retryCount = 1024;
            SeatState[] pool = new SeatState[retryCount];
            for (int i = 0; i < retryCount; i++) {
                pool[i] = new SeatState(s0.status(), s0.heldby(), s0.expiryMillis());
            }

            CountDownLatch go = new CountDownLatch(1);
            Thread retries = daemon("retries", () -> {
                await(go);
                for (SeatState st : pool) { ref.set(st); spinNanos(30_000); }   // ~30ms of retries
            });
            Thread confirmer = daemon("confirmer", () -> { await(go); seat.confirmSeat(userACopy, t); });
            retries.start();
            confirmer.start();
            go.countDown();

            retries.join(10_000);
            confirmer.join(2_000);
            boolean hung = confirmer.isAlive();

            if (hung) {                                                    // hand back a matching snapshot
                List<SeatState> candidates = new ArrayList<>();
                candidates.add(s0);
                candidates.addAll(List.of(pool));
                for (SeatState candidate : candidates) {
                    if (!confirmer.isAlive()) break;
                    ref.set(candidate);
                    confirmer.join(3);
                }
                confirmer.join(2_000);
                check(!confirmer.isAlive(), "could not un-wedge the confirmer thread");
            }

            check(!hung,
                    "confirmSeat() reads curState once, *outside* its retry loop, so a seat that changes "
                    + "state even once during the call wedges the thread: the CAS can never match the "
                    + "stale snapshot again, and the guard re-checked against that same snapshot never "
                    + "lets it out. Same defect as KB03/KB04, but this one sits on the checkout path, "
                    + "after the customer's card has already been charged.");
        });

        System.exit(summary());
    }

    /* ------------------------------------------------------------------ helpers */

    private static void spinNanos(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) Thread.onSpinWait();
    }

    private static void assertNoSeatLeftHeld(Show show) {
        for (Map.Entry<String, Seat> e : seatsOf(show).entrySet()) {
            SeatStatus st = statusOf(e.getValue());
            check(st != SeatStatus.HELD,
                    "seat " + e.getKey() + " is still HELD after every booking finished. " + dump(show));
        }
    }
}
