package Entities.SchedulingStrategy;

import Entities.Direction;

import java.util.NavigableSet;

public interface SchedulingStrategy {

    public Integer nextStop(int currentFloor, Direction curDirection, NavigableSet<Integer> upRequests, NavigableSet<Integer> downRequests);
}
