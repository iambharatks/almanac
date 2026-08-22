package Entities.SpotAllocation;

import Entities.ParkingFloor;
import Entities.ParkingSpot;
import Entities.Vehicle.Vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class FarthestFirstStrategy implements ParkingSpotAllocationStrategy {
    @Override
    public Optional<ParkingSpot> findParkingSpot(List<ParkingFloor> parkingFloors, Vehicle vehicle) {
        List<ParkingFloor> parkingSpots = new ArrayList<>(parkingFloors);
        Collections.reverse(parkingSpots);
        for(ParkingFloor floor : parkingSpots){
            Optional<ParkingSpot> parkingSpot = floor.getParkingSpot(vehicle);
            if(parkingSpot.isPresent()){
                return parkingSpot;
            }
        }
        System.out.println("No parking spot found\n FUCK OFF!\n");
        return Optional.empty();
    }
}
