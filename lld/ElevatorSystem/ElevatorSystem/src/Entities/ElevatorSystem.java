import java.util.List;

public class ElevatorSystem {
    private final List<Elevator> elevators;
    private final DispatchStrategy dispatchStrategy;

    public ElevatorSystem(List<Elevator> elevators, DispatchStrategy dispatchStrategy) {
        this.dispatchStrategy = dispatchStrategy;
        this.elevators = elevators;
    }

    public boolean requestElevator(int floor, Direction direction) {
        //TODO : use dispatch strategy here
        Elevator elevator = dispatchStrategy.selectElevators(elevators, request);
        elevator.addExternalRequest(request);
        return true;
    }

    public void tick(){
        for(Elevator elevator : elevators){
            elevator.tick();
        }
    }
}
