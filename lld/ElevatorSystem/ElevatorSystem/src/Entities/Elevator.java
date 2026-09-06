package Entities;

import Entities.SchedulingStrategy.SchedulingStrategy;

import java.util.Random;
import java.util.TreeSet;

public class Elevator {
    private final String elevatorId;
    private DoorState doorState;
    private int doorTicksRemaining;
    private int currentFloor;
    private Direction currentDirection;
    private final TreeSet<Integer> upRequests, downRequests;
    private final SchedulingStrategy schedulingStrategy;
    // for comparative tracking
    private int directionChanged = 0;
    public Elevator(String elevatorId, int currentFloor, SchedulingStrategy schedulingStrategy) {
        this.elevatorId = elevatorId;
        this.currentFloor = currentFloor;
        this.currentDirection = Direction.IDLE;
        upRequests = new TreeSet<>();
        downRequests = new TreeSet<>();
        this.schedulingStrategy = schedulingStrategy;
        doorState = DoorState.CLOSED;
        doorTicksRemaining = 1;
    }
    public String getElevatorId() {
        return  elevatorId;
    }
    public int getDirectionChanged(){
        return directionChanged;
    }

    public synchronized ElevatorState getElevatorState(){
        Integer highestPending =(upRequests.isEmpty())?null: upRequests.last();
        Integer lowestPending = (downRequests.isEmpty())?null: downRequests.first();
        int pendingRequests = upRequests.size() + downRequests.size();
        return new ElevatorState(currentFloor, currentDirection, highestPending, lowestPending,pendingRequests);
    }

    public synchronized void tick(){
        if(doorState == DoorState.OPEN){
            closeDoors();
            return;
        }

        Integer nextFloor = schedulingStrategy.nextStop(currentFloor, currentDirection, upRequests, downRequests);

        if(nextFloor == null){
           reverseorIdle();
           return;
        }
        if(nextFloor.intValue() == currentFloor){
            boolean hasUp   = upRequests.contains(nextFloor);
            boolean hasDown = downRequests.contains(nextFloor);

            Direction old = currentDirection;
            if (hasUp && !hasDown)      currentDirection = Direction.UP;
            else if (hasDown && !hasUp) currentDirection = Direction.DOWN;
            if (old != currentDirection && old != Direction.IDLE) directionChanged++;   // ← count it

            (currentDirection == Direction.UP ? upRequests : downRequests).remove(nextFloor);
            openDoors();
            return;
        }
        //Handle direction
        if(chooseDirection(nextFloor)){
            return;
        }

        // move towards it
        currentFloor += ((currentDirection == Direction.UP)?+1:-1);
    }

    private boolean chooseDirection(int floor){
        Direction old = currentDirection;
        if(currentFloor < floor){
            currentDirection = Direction.UP;
        }else if(currentFloor > floor){
            currentDirection = Direction.DOWN;
        }
        if(currentDirection != old && old != Direction.IDLE) {
            directionChanged++;
        }
        if(currentDirection != old)
            System.out.println("[" + elevatorId + "] " + old + " → " + currentDirection + " target=" + floor + " at=" + currentFloor);
        return currentDirection != old;
    }

    private void reverseorIdle(){
        if(upRequests.size() + downRequests.size() > 0 && currentDirection != Direction.IDLE){
            currentDirection = (currentDirection == Direction.UP) ? Direction.DOWN : Direction.UP;
            directionChanged++;
        }else{
            currentDirection = Direction.IDLE;
        }
    }

    private void openDoors(){
        System.out.println("[" + elevatorId + "] opening Doors for at "+currentFloor);
        doorState = DoorState.OPEN;
        doorTicksRemaining = 1;
    }

    private void closeDoors(){
        if(doorState == DoorState.OPEN){
            if(--doorTicksRemaining <= 0){
                doorState = DoorState.CLOSED;
                System.out.println("[" + elevatorId + "] closing Doors for at "+currentFloor);

            }
        }
    }

    public synchronized void addInternalRequest(int floor){
        if(currentFloor == floor){
            if(doorState == DoorState.OPEN) System.out.println("You are on the same floor, drop off!!");
            else {
                System.out.println("You request came a bit late, now stay and fuck around here!!");
                ((currentDirection == Direction.UP)?downRequests:upRequests).add(floor);
            }
            return;
        }
        if(currentFloor < floor){
            upRequests.add(floor);
        }else{
            downRequests.add(floor);
        }
    }

    public synchronized void addExternalRequest(ExternalRequest request) {
        ((request.direction() == Direction.UP)?upRequests:downRequests).add(request.floor());
    }

}


