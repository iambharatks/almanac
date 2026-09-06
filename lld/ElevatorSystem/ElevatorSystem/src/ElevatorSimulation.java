import Entities.*;
import Entities.DispatchStrategy.*;
import Entities.SchedulingStrategy.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * Runs the SAME request stream against two scheduling strategies and compares them.
 *
 * The point is not "does it work" -- it's WHY SCAN/LOOK exists. Nearest-first
 * oscillates: it reverses whenever a closer request appears behind the car, which
 * inflates both the reversal count and the time the last passenger waits.
 *
 * Metrics:
 *   ticksToDrain      total steps until every request is served
 *   directionChanges  reversals across all cars  -- the oscillation measure
 *   lastServedAt      when the FINAL request completed -- the starvation measure
 */
public class ElevatorSimulation {

    private static final int MAX_TICKS = 500;      // safety net against a stuck system

    public static void main(String[] args) {
        scenario("A. Simple upward sweep",
                List.of(req(0, 3, Direction.UP),
                        req(0, 7, Direction.UP),
                        req(0, 9, Direction.UP)));

        scenario("B. Oscillation trap (alternating above/below)",
                List.of(req(0,  9, Direction.UP),     // far request, issued first
                        req(2,  4, Direction.UP),     // then something closer appears
                        req(4,  2, Direction.DOWN),   // then behind the car
                        req(6,  6, Direction.UP),
                        req(8,  1, Direction.DOWN)));

        scenario("C. Mixed traffic, multiple cars",
                List.of(req(0,  2, Direction.UP),
                        req(0,  8, Direction.DOWN),
                        req(3,  5, Direction.UP),
                        req(5,  1, Direction.DOWN),
                        req(7,  9, Direction.DOWN),
                        req(9,  4, Direction.UP)));
    }

    /* ---------------- scenario runner ---------------- */

    static void scenario(String title, List<TimedRequest> requests) {
        System.out.println("\n============================================================");
        System.out.println(title);
        System.out.println("============================================================");

        Result scan    = run("SCAN/LOOK",     ScanScheduling::new,         requests, true);
        Result nearest = run("NEAREST-FIRST", NearestFirstScheduling::new, requests, true);

        System.out.printf("%n%-16s %10s %10s %12s%n", "", "ticks", "reversals", "lastServed");
        System.out.printf("%-16s %10d %10d %12d%n", scan.label,    scan.ticks,    scan.reversals,    scan.lastServed);
        System.out.printf("%-16s %10d %10d %12d%n", nearest.label, nearest.ticks, nearest.reversals, nearest.lastServed);

        String verdict = scan.reversals <= nearest.reversals
                ? "SCAN reversed fewer times (less oscillation)"
                : "nearest-first reversed fewer times -- check the scenario";
        System.out.println("  -> " + verdict);
    }

    /** Runs one simulation to completion. Set verbose=true to print every tick. */
    static Result run(String label, Supplier<SchedulingStrategy> strategyFactory,
                      List<TimedRequest> requests, boolean verbose) {
        System.out.println("Running " + label);

        List<Elevator> cars = List.of(
                new Elevator("E1", 0, strategyFactory.get()),
                new Elevator("E2", 5, strategyFactory.get()));      // fresh strategy per car

        ElevatorSystem system = new ElevatorSystem(cars, new NearestDispatch());

        // requests grouped by the tick they arrive
        Map<Integer, List<ExternalRequest>> schedule = new HashMap<>();
        for (TimedRequest tr : requests)
            schedule.computeIfAbsent(tr.tick(), k -> new ArrayList<>()).add(tr.request());

        int pendingBefore = 0;
        int lastServed = 0;
        int tick = 0;

        for (; tick < MAX_TICKS; tick++) {
            for (ExternalRequest r : schedule.getOrDefault(tick, List.of()))
                system.requestElevator(r);

            system.tick();

            int pendingNow = totalPending(cars);
            if (pendingNow < pendingBefore) lastServed = tick;      // something got served
            pendingBefore = pendingNow;

            if (verbose) System.out.println("t=" + tick + "  " + render(cars));

            boolean allArrived = tick >= schedule.keySet().stream().mapToInt(i -> i).max().orElse(0);
            if (allArrived && pendingNow == 0) break;
        }

        int reversals = cars.stream().mapToInt(Elevator::getDirectionChanged).sum();
        return new Result(label, tick, reversals, lastServed);
    }

    /* ---------------- helpers ---------------- */

    static int totalPending(List<Elevator> cars) {
        return cars.stream().mapToInt(c -> c.getElevatorState().pendingRequests()).sum();
    }

    static String render(List<Elevator> cars) {
        StringBuilder sb = new StringBuilder();
        for (Elevator c : cars) {
            ElevatorState s = c.getElevatorState();
            sb.append(String.format("[%s f=%-2d %-4s pend=%d] ",
                    c.getElevatorId(), s.floor(), s.curDirection(), s.pendingRequests()));
        }
        return sb.toString();
    }

    static TimedRequest req(int atTick, int floor, Direction dir) {
        return new TimedRequest(atTick, new ExternalRequest(floor, dir));
    }

    record TimedRequest(int tick, ExternalRequest request) {}
    record Result(String label, int ticks, int reversals, int lastServed) {}
}