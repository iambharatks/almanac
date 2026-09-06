package Entities;

import Entities.DispatchStrategy.DispatchStrategy;

import java.util.ArrayList;
import java.util.List;

public class ElevatorSystem {
    private final List<Elevator> elevators;
    private final DispatchStrategy dispatchStrategy;

    public ElevatorSystem(List<Elevator> elevators, DispatchStrategy dispatchStrategy) {
        this.dispatchStrategy = dispatchStrategy;
        this.elevators = elevators;
    }

    public void requestElevator(ExternalRequest request) {
        Elevator elevator = dispatchStrategy.selectElevator(elevators, request);
        if(elevator == null) {
            System.out.println("No elevator found");
            return;
        }
        elevator.addExternalRequest(request);
    }
    public List<ElevatorState> getAllElevatorsStates() {
        return elevators.stream().map(Elevator::getElevatorState).toList();
    }
    public void tick(){
        for(Elevator elevator : elevators){
            elevator.tick();
        }
    }
    public int totalDirectionChanged(){
        return elevators.stream().mapToInt(Elevator::getDirectionChanged).sum();
    }
}
