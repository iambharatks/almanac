package Entities;

import java.util.NavigableSet;

public record ElevatorState(int floor, Direction curDirection, Integer highestPending, Integer lowestPending, int pendingRequests) {

}
