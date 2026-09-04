import java.util.TreeSet;
import java.util.UUID;

public class Elevator {
    private final String elevatorId;
    private DoorState doorState;
    private int doorTicksRemaining;
    private int currentFloor;
    private Direction currentDirection;
    private final TreeSet<Integer> upRequests, downRequests;
    private final SchedulingStrategy schedulingStrategy;

    public Elevator(String elevatorId, int currentFloor, SchedulingStrategy schedulingStrategy) {
        this.elevatorId = elevatorId;
        this.currentFloor = 0;
        this.currentDirection = Direction.IDLE;
        upRequests = new TreeSet<>();
        downRequests = new TreeSet<>();
        this.schedulingStrategy = schedulingStrategy;
        doorState = DoorState.CLOSED;
        doorTicksRemaining = 1;
    }

    public synchronized void tick(){
        if(doorState == DoorState.OPEN){
            closeDoors();
            return;
        }
        if(currentDirection == Direction.IDLE){
            chooseDirection();
            return;
        }
        Integer nextFloor = (currentDirection == Direction.UP) ? upRequests.ceiling(currentFloor) : downRequests.floor(currentFloor);
        if(nextFloor == null){
            reverseOrIdle();
            return;
        }
        if(nextFloor.intValue() == currentFloor){
            openDoors();
            ((currentDirection == Direction.UP)? upRequests:downRequests).remove(currentFloor);
        }else{
            System.out.println("Moving towards " + nextFloor);
            currentFloor += ((currentDirection == Direction.UP)?1:-1);
        }
    }

    private void chooseDirection(){
        if(upRequests.isEmpty() && downRequests.isEmpty()){
            currentDirection = Direction.IDLE;
        }
        else if(upRequests.isEmpty()){
            currentDirection = Direction.DOWN;
        }
        else if(downRequests.isEmpty()){
            currentDirection = Direction.UP;
        }
        System.out.println("Current chosen direction: " + currentDirection);
    }

    private void reverseOrIdle(){
        chooseDirection();
    }

    private void openDoors(){
        System.out.println("Opening Doors for "+currentFloor);
        doorState = DoorState.OPEN;
        doorTicksRemaining = 1;
    }

    private void closeDoors(){
        if(doorState == DoorState.OPEN){
            if(--doorTicksRemaining <= 0){
                doorState = DoorState.CLOSED;
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

    public synchronized void addExternalRequest(int floor) {
        if(currentFloor == floor){
            if(doorState == DoorState.OPEN){
                System.out.println("You are on the same floor, drop off!!");
            }else{
                System.out.println("You request came a bit late, now stay and fuck around here!!");
                ((currentDirection == Direction.UP)?downRequests:upRequests).add(floor);
            }
            return;
        }
        ((currentFloor < floor)?upRequests:downRequests).add(floor);
        System.out.println("External request for floor "+ floor + " added in elevator "+elevatorId);
        return;
    }

}


