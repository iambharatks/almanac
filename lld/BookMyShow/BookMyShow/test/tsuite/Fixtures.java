package tsuite;

import Entities.Payment;
import Entities.PaymentResult;
import Entities.Show;
import Entities.Theatre;
import Entities.TimeSlot;
import Entities.Venue;
import Entities.Seat.Seat;
import PaymentStrategy.PaymentStrategy;
import PriceCalculationStrategy.ShowBasedPriceStrategy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic doubles + venue builders for the concurrency suite. */
public final class Fixtures {
    private Fixtures() {}

    public static final String THEATRE = "T-0";

    /* ------------------------------------------------------------- payment doubles */

    /** Always approves. Optional latency widens the window where seats sit HELD. */
    public static final class OkPay implements PaymentStrategy {
        private final long latencyMs;
        public final AtomicInteger charges = new AtomicInteger();
        public final AtomicInteger refunds = new AtomicInteger();

        public OkPay() { this(0); }
        public OkPay(long latencyMs) { this.latencyMs = latencyMs; }

        @Override public Payment pay(double amount, String userId) {
            Harness.sleep(latencyMs);
            charges.incrementAndGet();
            return new Payment(userId, amount, UUID.randomUUID().toString(), PaymentResult.SUCCESS);
        }
        @Override public void refund(String txnId) { refunds.incrementAndGet(); }
    }

    /** Always declines: exercises the hold-then-release rollback path. */
    public static final class FailPay implements PaymentStrategy {
        private final long latencyMs;
        public final AtomicInteger refunds = new AtomicInteger();

        public FailPay() { this(0); }
        public FailPay(long latencyMs) { this.latencyMs = latencyMs; }

        @Override public Payment pay(double amount, String userId) {
            Harness.sleep(latencyMs);
            return new Payment(userId, amount, null, PaymentResult.FAILURE);
        }
        @Override public void refund(String txnId) { refunds.incrementAndGet(); }
    }

    /** Declines a fixed fraction of calls. */
    public static final class FlakyPay implements PaymentStrategy {
        private final double failureRate;
        private final long latencyMs;
        public final AtomicInteger charges = new AtomicInteger();
        public final AtomicInteger refunds = new AtomicInteger();

        public FlakyPay(double failureRate, long latencyMs) {
            this.failureRate = failureRate;
            this.latencyMs = latencyMs;
        }
        @Override public Payment pay(double amount, String userId) {
            Harness.sleep(latencyMs);
            if (ThreadLocalRandom.current().nextDouble() < failureRate) {
                return new Payment(userId, amount, null, PaymentResult.FAILURE);
            }
            charges.incrementAndGet();
            return new Payment(userId, amount, UUID.randomUUID().toString(), PaymentResult.SUCCESS);
        }
        @Override public void refund(String txnId) { refunds.incrementAndGet(); }
    }

    /**
     * Parks exactly one user inside pay() until the test releases it. Everyone else sails through.
     * This is how we get a deterministic "something happened while the seats were HELD and paid for"
     * window, which is where the interesting Venue.bookSeats bugs live.
     */
    public static final class GatedPay implements PaymentStrategy {
        private final String gatedUser;
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
        public final AtomicInteger charges = new AtomicInteger();
        public final AtomicInteger refunds = new AtomicInteger();

        public GatedPay(String gatedUser) { this.gatedUser = gatedUser; }

        @Override public Payment pay(double amount, String userId) {
            if (gatedUser.equals(userId)) {
                entered.countDown();
                Harness.await(release);
            }
            charges.incrementAndGet();
            return new Payment(userId, amount, UUID.randomUUID().toString(), PaymentResult.SUCCESS);
        }
        @Override public void refund(String txnId) { refunds.incrementAndGet(); }
    }

    /* --------------------------------------------------------------- venue builders */

    private static final AtomicInteger SLOT_SEQ = new AtomicInteger();

    /** Single-theatre venue whose seats are exactly {@code seatIds}. */
    public static Venue venue(PaymentStrategy pay, String... seatIds) {
        List<Seat> seats = new ArrayList<>();
        for (String id : seatIds) seats.add(new Seat(id));
        Theatre theatre = new Theatre(THEATRE, seats);
        return new Venue("PVR", "Agra", List.of(theatre),
                new ShowBasedPriceStrategy(100, 1.0, 1.0), pay);
    }

    /** Venue with n seats named A00..A(n-1); lexical order == natural order, which matters for hold ordering. */
    public static Venue venueWithSeats(PaymentStrategy pay, int n) {
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) ids[i] = "A%02d".formatted(i);
        return venue(pay, ids);
    }

    public static List<String> seatIds(int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add("A%02d".formatted(i));
        return ids;
    }

    /** A show on a slot that never collides with any other slot handed out by this helper. */
    public static Show newShow(Venue venue, String name) {
        return venue.addShow(THEATRE, name, freshSlot()).orElseThrow(
                () -> new IllegalStateException("addShow failed for " + name));
    }

    public static TimeSlot freshSlot() {
        // TimeSlot.overlaps() treats touching slots as overlapping, so leave an hour of daylight.
        int i = SLOT_SEQ.getAndIncrement();
        LocalDateTime start = LocalDateTime.of(2030, 1, 1, 0, 0).plusHours(3L * i);
        return new TimeSlot(start, start.plusHours(2));
    }

    /* ------------------------------------------------------------------- utilities */

    /** k distinct seat ids drawn from the first n seats. */
    public static List<String> randomSeats(int n, int k) {
        List<String> all = new ArrayList<>(seatIds(n));
        java.util.Collections.shuffle(all, ThreadLocalRandom.current());
        List<String> pick = new ArrayList<>(all.subList(0, k));
        return pick;
    }

    public static List<String> list(String... s) { return Arrays.asList(s); }
}
