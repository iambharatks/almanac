package Entities.SpotAllocation;
import Entities.ParkingFloor;
import Entities.ParkingSpot;
import Entities.Vehicle.Vehicle;

import java.util.List;
import java.util.Optional;

public interface ParkingSpotAllocationStrategy {
    Optional<ParkingSpot> findParkingSpot(List<ParkingFloor> parkingFloors, Vehicle vehicle);
}
