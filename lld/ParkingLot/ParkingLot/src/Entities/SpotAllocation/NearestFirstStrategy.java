package Entities.SpotAllocation;

import Entities.ParkingFloor;
import Entities.ParkingSpot;
import Entities.Vehicle.Vehicle;

import java.util.List;
import java.util.Optional;

public class NearestFirstStrategy implements ParkingSpotAllocationStrategy {

    @Override
    public Optional<ParkingSpot> findParkingSpot(List<ParkingFloor> parkingFloors, Vehicle vehicle) {
        for(ParkingFloor parkingFloor: parkingFloors){
            Optional<ParkingSpot> parkingSpot = parkingFloor.getParkingSpot(vehicle);
            if(parkingSpot.isPresent()){
                return parkingSpot;
            }
        }
        System.out.println("No parking spot found\n FUCK OFF!\n");
        return Optional.empty();
    }
}
