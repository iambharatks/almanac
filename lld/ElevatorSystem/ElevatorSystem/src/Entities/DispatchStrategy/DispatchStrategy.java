package Entities.DispatchStrategy;

import Entities.Elevator;
import Entities.ExternalRequest;

import java.util.List;

public interface DispatchStrategy {
    public Elevator selectElevator(List<Elevator> elevators, ExternalRequest request);
}
