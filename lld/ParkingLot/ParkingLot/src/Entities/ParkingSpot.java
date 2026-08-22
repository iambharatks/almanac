package Entities;

import Entities.Vehicle.Vehicle;
import Entities.Vehicle.VehicleSize;
import lombok.Data;

import java.beans.VetoableChangeListener;

@Data
public final class ParkingSpot {
    private final String spotId;
    private final VehicleSize spotSize;
    private Boolean isOccupied = false;
    private Vehicle vehicle;

    public Boolean parkVehicle(Vehicle vehicle){
        if(isOccupied){
            return false;
        }
//        try { Thread.sleep(100); } catch (InterruptedException e) {}
        this.vehicle = vehicle;
        this.isOccupied = true;
        return true;
    }
    public Boolean unparkVehicle(Vehicle vehicle){
        if(!isOccupied){
            return false;
        }
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        this.vehicle = null;
        this.isOccupied = false;
        return true;
    }
    public Boolean canFitVehicle(Vehicle vehicle){
        return !isOccupied && vehicle.getVehicleSize() == spotSize;
    }
}
