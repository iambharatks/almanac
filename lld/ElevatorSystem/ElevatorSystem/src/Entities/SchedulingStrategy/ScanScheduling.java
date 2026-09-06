package Entities.SchedulingStrategy;

import Entities.Direction;

import java.util.NavigableSet;

public class ScanScheduling implements SchedulingStrategy {

    @Override
    public Integer nextStop(int currentFloor, Direction curDirection, NavigableSet<Integer> upRequests, NavigableSet<Integer> downRequests) {
        if(curDirection == Direction.IDLE){
            Integer above = upRequests.ceiling(currentFloor);
            Integer below = downRequests.floor(currentFloor);
            if(above == null && below == null) return null;
            if(below == null) return above;
            if(above == null) return below;
            if((above-currentFloor) <= (currentFloor-below)){
                return above;
            }else return below;
        }
        Integer nextFloor = null;
        if(curDirection == Direction.UP){
            Integer a =  upRequests.ceiling(currentFloor);
            Integer b =  downRequests.ceiling(currentFloor);
            if(a == null) return b;
            if(b == null) return a;
            return Math.min(a, b);
        }else{
            Integer a =  upRequests.floor(currentFloor);
            Integer b =  downRequests.floor(currentFloor);
            if(a == null) return b;
            if(b == null) return a;
            return Math.max(a, b);
        }
    }
}
