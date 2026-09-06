package Entities.DispatchStrategy;

import Entities.Direction;
import Entities.Elevator;
import Entities.ElevatorState;
import Entities.ExternalRequest;

import java.util.List;

public final class NearestDispatch implements DispatchStrategy {

    @Override
    public Elevator selectElevator(List<Elevator> elevators, ExternalRequest request) {
        Elevator selected = null;
        int bestScore = Integer.MAX_VALUE;
        for(Elevator elevator : elevators) {
            ElevatorState state = elevator.getElevatorState();
            int score = 10*cost(state, request)+state.pendingRequests();
//            System.out.println("dispatching for " + request + " for " + elevator.getElevatorId() + " car with score " + score);
            if(score < bestScore) {
                bestScore = score;
                selected = elevator;
            }
        }
        return selected;
    }

    private int cost(ElevatorState state, ExternalRequest request) {
        if(state.curDirection() == Direction.IDLE){
            return Math.abs(state.floor()-request.floor());
        }
        int high =  Math.max(state.floor(),request.floor());
        int low = Math.min(state.floor(),request.floor());
        if(state.highestPending() != null){
            high = Math.max(state.highestPending(),high);
        }
        if(state.lowestPending() != null){
            low = Math.min(state.lowestPending(),low);
        }
        if(state.curDirection() == request.direction()){
            return switch(state.curDirection()){
                case UP-> {
                    if (state.floor() <= request.floor()) {
                         yield request.floor() - state.floor();
                    } else
                         yield 2 * (high - low) + state.floor() - request.floor();
                    }
                case DOWN ->{
                    if(state.floor() >= request.floor()){
                        yield  state.floor() - request.floor();
                    }else{
                        yield 2*(high-low) + request.floor() - state.floor();
                    }
                }
                default -> throw new IllegalStateException("Unexpected value: " + state.curDirection());
            };
        }else{
            return switch(state.curDirection()){
                case UP-> (2 * high) - (state.floor() + request.floor());
                case DOWN -> request.floor() + state.floor() - 2*low;
                default -> throw new IllegalStateException("Unexpected value: " + state.curDirection());
            };
        }
    }

}
