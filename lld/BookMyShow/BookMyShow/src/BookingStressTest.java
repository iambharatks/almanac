import Entities.*;
import Entities.Seat.Seat;
import PaymentStrategy.*;
import PriceCalculationStrategy.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stress test for the booking system.
 *
 * A concurrency test that merely RUNS proves nothing -- races are intermittent.
 * Every scenario below ends with an INVARIANT ASSERTION that can actually fail.
 *
 * THE HARD INVARIANT: no seat appears in two tickets. Everything else is secondary.
 *
 * NOTE: adjust method names to match your API --
 *   venue.bookSeats(showId, seatIds, userId) -> Optional<Ticket>
 *   venue.getSeatsForShow(showId)            -> Optional<List<String>>
 *   ticket.getSeatIds()                      -> List<String>
 */
public class BookingStressTest {
    // showId -> a human-readable label, so the menu can show something meaningful
    private static final Map<String, String> showLabels = new LinkedHashMap<>();
    private static String showId;
    public static void main(String[] args) throws Exception {
        Venue venue = buildVenue();

        showLabels.forEach((s,v)->System.out.println(s+v));
        System.out.println(showId);
        contentionOnSameSeats(venue, showId);
        allOrNothing(venue, showId);
        randomLoad(venue, showId);
    }
    /* ---------------- setup ---------------- */

    private static Venue buildVenue() {
        PriceCalculationStrategy pricing = new ShowBasedPriceStrategy(200, 1.50, 2.0);
        PaymentStrategy payment = new MockPaymentStrategy(0.3, 500);   // 30% decline, 800ms latency

        Random random = new Random();
        List<Theatre> theatres = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            int capacity = random.nextInt(41) + 10;                     // 10..50
            List<Seat> seats = new ArrayList<>();
            for (int s = 0; s < capacity; s++) {
                seats.add(new Seat("S-" + t + "-" + s));
            }
            theatres.add(new Theatre("T-" + t, seats));
        }

        Venue venue = new Venue("PVR", "Agra", theatres, pricing, payment);

        List<String> movies = List.of("SpiderMan", "Odyssey", "Troy", "Good Will Hunting");

        // NON-OVERLAPPING slots: show i in theatre t starts at hour (i*3), runs 2h.
        // Each theatre gets its own showId per movie -- IDs must be unique across the venue.
        for (int t = 0; t < theatres.size(); t++) {
            String theatreId = theatres.get(t).getTheatreId();
            for (int m = 0; m < movies.size(); m++) {
                LocalDateTime start = LocalDateTime.of(2026, 8, 1, 9 + (m * 3), 0);
                TimeSlot slot = new TimeSlot(start, start.plusHours(2));

                Show show = venue.addShow(theatreId,movies.get(m), slot).get();
                System.out.println("Added following show "+show.getShowName()+ " at theater "+theatreId);
                showId = show.getShowId();
                showLabels.put(show.getShowId(), "%-18s %s  %s".formatted(
                        movies.get(m), theatreId, start.toLocalTime()));
            }
        }
        return venue;
    }
    /* ================================================================
       TEST 1 — every thread wants the SAME seats.
       Correct: exactly ONE ticket. Anything else is a double-sell.
       ================================================================ */
    static void contentionOnSameSeats(Venue venue, String showId) throws Exception {
        final int THREADS = 100;
        List<String> contested = List.of("S-0-1", "S-0-2", "S-0-3");

        List<Ticket> tickets = runConcurrently(THREADS, i ->
                venue.bookSeats(showId, contested, "user-" + i,System.currentTimeMillis()));

        System.out.println("\n=== TEST 1: contention on identical seats ===");
        System.out.println("threads: " + THREADS + "   tickets issued: " + tickets.size());

        boolean pass = tickets.size() <= 1;                  // <=1: payment may legitimately decline
        System.out.println(pass
                ? "PASS: at most one booking won the contested seats"
                : "FAIL: " + tickets.size() + " tickets for the same seats -- DOUBLE SELL");

        assertNoSeatSoldTwice(tickets);
    }

    /* ================================================================
       TEST 2 — all-or-nothing.
       One seat in each request is already sold, so EVERY request must
       fail, and no seat from any request may end up held or booked.
       ================================================================ */
    static void allOrNothing(Venue venue, String showId) throws Exception {
        final int THREADS = 50;

        // seat S-0-1 was taken in test 1; every request below includes it
        List<String> withTakenSeat = List.of("S-0-1", "S-0-90", "S-0-91");

        List<Ticket> tickets = runConcurrently(THREADS, i ->
                venue.bookSeats(showId, withTakenSeat, "user-b-" + i,System.currentTimeMillis() ));

        System.out.println("\n=== TEST 2: all-or-nothing ===");
        System.out.println("tickets issued: " + tickets.size() + " (expected 0)");

        // the OTHER seats must have been rolled back and still be available
        List<String> available = venue.getSeatsForShow(showId).orElse(List.of());
        boolean rolledBack = available.contains("S-0-90") && available.contains("S-0-91");

        System.out.println(tickets.isEmpty() && rolledBack
                ? "PASS: no partial bookings; unclaimed seats released"
                : "FAIL: partial booking or leaked hold");
    }

    /* ================================================================
       TEST 3 — random load. Measures throughput and latency, and
       re-checks the hard invariant across everything that succeeded.
       ================================================================ */
    static void randomLoad(Venue venue, String showId) throws Exception {
        final int THREADS = 200;
        List<String> pool = venue.getSeatsForShow(showId).orElse(List.of());
        int poolBefore = pool.size();/* size before the test */;

        if (pool.isEmpty()) { System.out.println("no seats left to load-test"); return; }

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();

        List<Ticket> tickets = runConcurrently(THREADS, i -> {
            List<String> pick = randomSeats(pool, 2, i);
            long t0 = System.nanoTime();
            Optional<Ticket> result = venue.bookSeats(showId, pick, "user-c-" + i,System.currentTimeMillis());
            latencies.add(System.nanoTime() - t0);
            return result;
        });

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("\n=== TEST 3: random load ===");
        System.out.printf("requests: %d   succeeded: %d   failed: %d%n",
                THREADS, tickets.size(), THREADS - tickets.size());
        System.out.printf("wall time: %d ms   throughput: %.1f req/s%n",
                elapsedMs, THREADS * 1000.0 / Math.max(1, elapsedMs));
        printLatencies(latencies);

        assertNoSeatSoldTwice(tickets);
        List<String> after = venue.getSeatsForShow(showId).orElse(List.of());
        System.out.println("available: " + after.size() + " / " + poolBefore
                + "   booked: " + tickets.size() * 2);
    }

    /* ================================================================
       THE ASSERTION THAT MATTERS
       ================================================================ */
    static void assertNoSeatSoldTwice(List<Ticket> tickets) {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (Ticket t : tickets)
            for (String seat : t.bookedSeats())
                if (!seen.add(seat)) duplicates.add(seat);

        System.out.println(duplicates.isEmpty()
                ? "  invariant OK: " + seen.size() + " distinct seats, no seat sold twice"
                : "  INVARIANT VIOLATED: seats sold more than once -> " + duplicates);
    }

    /* ---------------- harness ---------------- */

    /** Fires N tasks simultaneously via a start gate and returns the successful tickets. */
    static List<Ticket> runConcurrently(int threads, java.util.function.IntFunction<Optional<Ticket>> task)
            throws Exception {

        CountDownLatch startGate = new CountDownLatch(1);         // maximise overlap
        CountDownLatch done      = new CountDownLatch(threads);
        ExecutorService pool     = Executors.newFixedThreadPool(threads);
        List<Ticket> tickets     = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors     = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    task.apply(id).ifPresent(tickets::add);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    errors.incrementAndGet();                      // exceptions are bugs, not results
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        done.await();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        if (errors.get() > 0)
            System.out.println("  WARNING: " + errors.get() + " requests threw an exception");

        return new ArrayList<>(tickets);
    }

    static List<String> randomSeats(List<String> pool, int count, int seed) {
        Random r = new Random(seed);
        List<String> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, r);
        return copy.subList(0, Math.min(count, copy.size()));
    }

    static void printLatencies(List<Long> nanos) {
        if (nanos.isEmpty()) return;
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        System.out.printf("latency ms  p50=%.1f  p95=%.1f  p99=%.1f  max=%.1f%n",
                ms(sorted, 0.50), ms(sorted, 0.95), ms(sorted, 0.99), ms(sorted, 1.0));
    }

    static double ms(List<Long> sorted, double pct) {
        int idx = Math.min(sorted.size() - 1, (int) (sorted.size() * pct));
        return sorted.get(idx) / 1_000_000.0;
    }
}