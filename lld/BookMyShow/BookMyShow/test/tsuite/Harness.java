package tsuite;

import Entities.Show;
import Entities.Ticket;
import Entities.Venue;
import Entities.Seat.Seat;
import Entities.Seat.SeatState;
import Entities.Seat.SeatStatus;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tiny zero-dependency test harness for the BookMyShow concurrency suite.
 *
 * Design notes:
 *  - The production classes print heavily to stdout, so every test body runs with stdout/stderr
 *    swapped out. Reporting always goes to {@link #CONSOLE}.
 *  - {@link #HOOK} is the interesting bit: it is a stdout implementation that can run an arbitrary
 *    action at the exact moment a given SUT line is printed. Seat.releaseHeld/releaseBooked print
 *    *between* their state.get() and their CAS loop, so the hook gives us a 100% deterministic
 *    interleaving for those races without touching production code.
 *  - Every worker thread is a daemon, and the hang tests repair the seat state afterwards so a
 *    spinning thread is never left burning a core for the rest of the run.
 */
public final class Harness {
    private Harness() {}

    /* ------------------------------------------------------------------ console */

    public static final PrintStream CONSOLE = System.out;
    private static final PrintStream REAL_ERR = System.err;
    private static final PrintStream SINK = new PrintStream(OutputStream.nullOutputStream(), false);

    /** stdout that can fire a callback at the instant the SUT prints a marker line. */
    public static final class Hook extends PrintStream {
        private volatile String marker;
        private volatile Runnable action;
        private volatile Thread onlyThread;
        private final AtomicInteger fired = new AtomicInteger();

        Hook() { super(OutputStream.nullOutputStream(), false); }

        /** Fire {@code action} once, on {@code onlyThread}, when a line starting with {@code marker} is printed. */
        public void arm(String marker, Thread onlyThread, Runnable action) {
            this.fired.set(0);
            this.action = action;
            this.onlyThread = onlyThread;
            this.marker = marker;
        }

        public void disarm() { marker = null; action = null; onlyThread = null; }

        public boolean didFire() { return fired.get() > 0; }

        @Override
        public void println(String s) {
            String m = marker;
            if (m == null || s == null || !s.startsWith(m)) return;
            Thread only = onlyThread;
            if (only != null && only != Thread.currentThread()) return;
            if (!fired.compareAndSet(0, 1)) return;
            Runnable a = action;
            if (a != null) a.run();
        }
    }

    public static final Hook HOOK = new Hook();

    public static void quiet()  { System.setOut(SINK); System.setErr(SINK); }
    public static void hooked() { System.setOut(HOOK); System.setErr(SINK); }
    public static void loud()   { System.setOut(CONSOLE); System.setErr(REAL_ERR); }
    public static void say(String s) { CONSOLE.println(s); }

    /* --------------------------------------------------------------- assertions */

    public static final class Failed extends RuntimeException {
        public Failed(String m) { super(m); }
    }

    public static void check(boolean cond, String msg) {
        if (!cond) throw new Failed(msg);
    }

    public static void checkEq(Object actual, Object expected, String msg) {
        if (!java.util.Objects.equals(actual, expected)) {
            throw new Failed(msg + "  [expected=" + expected + " actual=" + actual + "]");
        }
    }

    /* ------------------------------------------------------------------ running */

    public enum Kind { NORMAL, KNOWN_BUG }

    public record Result(String id, String name, Kind kind, String outcome, String detail, long millis) {
        boolean bad() { return outcome.equals("FAIL") || outcome.equals("ERROR"); }
    }

    public static final List<Result> RESULTS = new ArrayList<>();

    public interface Body { void run() throws Exception; }

    /** A test that the design is expected to pass. */
    public static void test(String id, String name, Body body) { run(id, name, Kind.NORMAL, body); }

    /** A test that documents a real defect: XFAIL means "bug still there", XPASS means "bug fixed". */
    public static void knownBug(String id, String name, Body body) { run(id, name, Kind.KNOWN_BUG, body); }

    private static void run(String id, String name, Kind kind, Body body) {
        long t0 = System.nanoTime();
        String outcome;
        String detail = "";
        try {
            quiet();
            body.run();
            outcome = kind == Kind.KNOWN_BUG ? "XPASS" : "PASS";
            if (kind == Kind.KNOWN_BUG) detail = "bug no longer reproduces - the fix landed, retire this test";
        } catch (Failed f) {
            outcome = kind == Kind.KNOWN_BUG ? "XFAIL" : "FAIL";
            detail = f.getMessage();
        } catch (Throwable t) {
            outcome = kind == Kind.KNOWN_BUG ? "XFAIL" : "ERROR";
            detail = t.toString();
        } finally {
            HOOK.disarm();
            loud();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        RESULTS.add(new Result(id, name, kind, outcome, detail, ms));
        String tag = switch (outcome) {
            case "PASS"  -> "  PASS ";
            case "XFAIL" -> "  BUG  ";
            case "XPASS" -> " FIXED ";
            case "FAIL"  -> "  FAIL ";
            default      -> " ERROR ";
        };
        say("[%s] %-6s %-58s %5d ms".formatted(tag, id, name, ms));
        if (!detail.isEmpty()) say("          -> " + detail);
    }

    public static int summary() {
        say("");
        say("=".repeat(96));
        long pass  = RESULTS.stream().filter(r -> r.outcome.equals("PASS")).count();
        long xfail = RESULTS.stream().filter(r -> r.outcome.equals("XFAIL")).count();
        long xpass = RESULTS.stream().filter(r -> r.outcome.equals("XPASS")).count();
        long bad   = RESULTS.stream().filter(Result::bad).count();
        say("%d passed, %d known bugs reproduced, %d known bugs now fixed, %d unexpected failures"
                .formatted(pass, xfail, xpass, bad));
        say("=".repeat(96));
        return bad == 0 ? 0 : 1;
    }

    /* -------------------------------------------------------------- concurrency */

    public interface IdxTask<T> { T run(int i) throws Exception; }

    /** Start n daemon threads released together by a barrier; fail the test on hang or exception. */
    public static <T> List<T> race(int n, IdxTask<T> task) { return race(n, task, 30_000); }

    public static <T> List<T> race(int n, IdxTask<T> task, long timeoutMs) {
        CyclicBarrier gate = new CyclicBarrier(n);
        List<T> out = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errs = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    gate.await();
                    T r = task.run(idx);
                    if (r != null) out.add(r);
                } catch (Throwable e) {
                    errs.add(e);
                }
            }, "race-" + i);
            t.setDaemon(true);
            threads.add(t);
        }
        threads.forEach(Thread::start);
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Thread t : threads) {
            try { t.join(Math.max(1, deadline - System.currentTimeMillis())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        for (Thread t : threads) {
            if (t.isAlive()) throw new Failed("HANG: " + t.getName() + " did not finish within " + timeoutMs + "ms");
        }
        if (!errs.isEmpty()) {
            Throwable e = errs.get(0);
            throw new Failed("worker threw " + e + (errs.size() > 1 ? " (+" + (errs.size() - 1) + " more)" : ""));
        }
        return new ArrayList<>(out);
    }

    public static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void await(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /* ------------------------------------------------------------------- probes */

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            throw new IllegalStateException("probe field " + c.getSimpleName() + "." + name + " missing", e);
        }
    }

    private static final Field SEAT_STATE    = field(Seat.class, "state");
    private static final Field SHOW_SEATS    = field(Show.class, "seats");
    private static final Field SHOW_TICKETS  = field(Show.class, "activeTickets");
    private static final Field VENUE_SHOWS   = field(Venue.class, "shows");

    @SuppressWarnings("unchecked")
    public static AtomicReference<SeatState> stateRef(Seat s) {
        try { return (AtomicReference<SeatState>) SEAT_STATE.get(s); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    public static SeatState stateOf(Seat s)      { return stateRef(s).get(); }
    public static SeatStatus statusOf(Seat s)    { return stateOf(s).status(); }
    public static String holderOf(Seat s)        { return stateOf(s).heldby(); }

    @SuppressWarnings("unchecked")
    public static Map<String, Seat> seatsOf(Show show) {
        try { return (Map<String, Seat>) SHOW_SEATS.get(show); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Ticket> ticketsOf(Show show) {
        try { return (Map<String, Ticket>) SHOW_TICKETS.get(show); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Show> showsOf(Venue v) {
        try { return (Map<String, Show>) VENUE_SHOWS.get(v); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    /** Human-readable dump of every seat in a show, for failure messages. */
    public static String dump(Show show) {
        StringBuilder sb = new StringBuilder();
        seatsOf(show).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(stateOf(e.getValue())).append("  "));
        return sb.toString().trim();
    }
}
