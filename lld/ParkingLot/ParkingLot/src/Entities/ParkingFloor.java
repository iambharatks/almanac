package Entities;

import Entities.Vehicle.Vehicle;
import lombok.*;
import java.util.Map;
import java.util.Optional;

@Data
public final class ParkingFloor {
    private String floorId;
    private Map<String,ParkingSpot> parkingSpots;

    public ParkingFloor(String floorId, Map<String,ParkingSpot> parkingSpots) {
        this.parkingSpots = Map.copyOf(parkingSpots);
        this.floorId = floorId;
    }
    public Optional<ParkingSpot> getParkingSpot(Vehicle vehicle) {
        return parkingSpots.values().stream().filter(parkingSpot -> !parkingSpot.getIsOccupied() && parkingSpot.canFitVehicle(vehicle)).findFirst();
    }
}
