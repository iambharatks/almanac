package Entities.SchedulingStrategy;

import Entities.Direction;

import java.util.Arrays;
import java.util.List;
import java.util.NavigableSet;


public class NearestFirstScheduling implements SchedulingStrategy {
    @Override
    public Integer nextStop(int currentFloor, Direction curDirection, NavigableSet<Integer> upRequests, NavigableSet<Integer> downRequests) {
        Integer best = null;
        for (NavigableSet<Integer> set : List.of(upRequests, downRequests)) {
            for (Integer f : Arrays.asList(set.ceiling(currentFloor), set.floor(currentFloor))) {
                if (f == null) continue;
                if (best == null
                        || Math.abs(f - currentFloor) < Math.abs(best - currentFloor)
                        || (Math.abs(f - currentFloor) == Math.abs(best - currentFloor) && f < best))
                    best = f;                     // deterministic tiebreak
            }
        }
        return best;
    }
}
