import Entities.ParkingFloor;
import Entities.ParkingSpot;
import Entities.Ticket;
import Entities.Vehicle.Vehicle;
import Entities.Vehicle.VehicleSize;

import java.util.*;
import java.util.concurrent.*;

/**
 * Two tests, each with an ASSERTION. A concurrency test that only runs
 * without crashing proves nothing -- races are intermittent. The point
 * is the invariant check at the end.
 *
 *   TEST 1  contention   : 5 spots, 30 threads -> exactly 5 winners, all distinct spots
 *   TEST 2  churn        : park/unpark repeatedly -> every spot free at the end
 */
public class ConcurrencyTest {

    public static void main(String[] args) throws Exception {
        ParkingLot lot = ParkingLot.getInstance();

        // ONE floor, exactly 5 MEDIUM spots. Small on purpose: heavy contention.
        Map<String, ParkingSpot> spots = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            spots.put("M-" + i, new ParkingSpot("M-" + i, VehicleSize.MEDIUM));
        }
        lot.addFloor(new ParkingFloor("1", spots));

        testNoDoubleAllocation(lot);
        testNoSpotLeak(lot);
    }

    /* ---------------------------------------------------------------
       TEST 1 — 30 threads race for 5 spots.
       Correct behaviour: exactly 5 tickets, each on a DIFFERENT spot.
       A race shows up as two tickets holding the same spotId.
       --------------------------------------------------------------- */
    static void testNoDoubleAllocation(ParkingLot lot) throws Exception {
        final int THREADS = 30, SPOTS = 5;

        CountDownLatch startGate = new CountDownLatch(1);     // fire all at once
        CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Ticket> issued = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < THREADS; i++) {
            final String plate = "CAR-" + i;
            pool.submit(() -> {
                try {
                    startGate.await();                        // maximise contention
                    lot.parkVehicle(new Vehicle(plate, VehicleSize.MEDIUM))
                            .ifPresent(issued::add);
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

        // ---- ASSERTIONS ----
        System.out.println("\n=== TEST 1: no double allocation ===");
        System.out.println("tickets issued: " + issued.size() + " (expected " + SPOTS + ")");

        Set<String> usedSpots = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Ticket t : issued) {
            String id = t.getParkingSpot().getSpotId();
            if (!usedSpots.add(id)) duplicates.add(id);        // add() false => already seen
        }

        if (issued.size() != SPOTS) {
            System.out.println("FAIL: expected " + SPOTS + " winners, got " + issued.size());
        } else if (!duplicates.isEmpty()) {
            System.out.println("FAIL: spot allocated twice -> " + duplicates);
        } else {
            System.out.println("PASS: " + SPOTS + " distinct spots, no double allocation");
        }

        // clean up for the next test
        for (Ticket t : issued) lot.unparkVehicle(t.getVehicle().getLicensePlate());
    }

    /* ---------------------------------------------------------------
       TEST 2 — 20 threads park then immediately unpark, 50 rounds each.
       Correct behaviour: at the end, all 5 spots are free again.
       A leak (spot never released) or a double-free shows up as fewer
       than 5 spots available afterwards.
       --------------------------------------------------------------- */
    static void testNoSpotLeak(ParkingLot lot) throws Exception {
        final int THREADS = 20, ROUNDS = 50;

        CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    for (int r = 0; r < ROUNDS; r++) {
                        String plate = "T" + id + "-R" + r;
                        Optional<Ticket> t = lot.parkVehicle(new Vehicle(plate, VehicleSize.MEDIUM));
                        if (t.isPresent()) {
                            lot.unparkVehicle(plate);          // always release
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // ---- ASSERTION: black-box check. If all 5 spots are free,
        //      5 fresh vehicles must all park successfully.
        System.out.println("\n=== TEST 2: no spot leak after churn ===");
        int reclaimed = 0;
        List<String> plates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String plate = "FINAL-" + i;
            if (lot.parkVehicle(new Vehicle(plate, VehicleSize.MEDIUM)).isPresent()) {
                reclaimed++;
                plates.add(plate);
            }
        }
        System.out.println("spots reclaimed: " + reclaimed + " / 5");
        System.out.println(reclaimed == 5
                ? "PASS: every spot released correctly"
                : "FAIL: " + (5 - reclaimed) + " spot(s) leaked or double-freed");

        for (String p : plates) lot.unparkVehicle(p);
    }
}