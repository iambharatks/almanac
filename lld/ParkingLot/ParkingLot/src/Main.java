import Entities.ParkingFloor;
import Entities.ParkingSpot;
import Entities.Ticket;
import Entities.Vehicle.Vehicle;
import Entities.Vehicle.VehicleSize;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
        //TIP Press <shortcut actionId="ShowIntentionActions"/> with your caret at the highlighted text
        // to see how IntelliJ IDEA suggests fixing it.
        System.out.printf("Hello and welcome!");
        ParkingLot parkingLot = ParkingLot.getInstance();
        Map<String, ParkingSpot> parkingSpotMap = new HashMap<>();
        parkingSpotMap.putIfAbsent("A-1",new ParkingSpot("A-1", VehicleSize.MEDIUM));
        parkingSpotMap.putIfAbsent("A-2",new ParkingSpot("A-2", VehicleSize.MEDIUM));
        parkingSpotMap.putIfAbsent("A-3",new ParkingSpot("A-3", VehicleSize.LARGE));
        parkingSpotMap.putIfAbsent("A-4",new ParkingSpot("A-4", VehicleSize.SMALL));
        parkingSpotMap.putIfAbsent("B-1",new ParkingSpot("B-1", VehicleSize.SMALL));
        parkingSpotMap.putIfAbsent("B-2",new ParkingSpot("B-2", VehicleSize.MEDIUM));
        parkingSpotMap.putIfAbsent("B-3",new ParkingSpot("B-3", VehicleSize.LARGE));
        ParkingFloor parkingFloor1 = new ParkingFloor("1",parkingSpotMap);
        parkingSpotMap = new HashMap<>();
        parkingSpotMap.putIfAbsent("A-1",new ParkingSpot("A-1", VehicleSize.MEDIUM));
        parkingSpotMap.putIfAbsent("A-2",new ParkingSpot("A-2", VehicleSize.MEDIUM));
        parkingSpotMap.putIfAbsent("A-3",new ParkingSpot("A-3", VehicleSize.LARGE));
        parkingSpotMap.putIfAbsent("A-4",new ParkingSpot("A-4", VehicleSize.SMALL));
        parkingSpotMap.putIfAbsent("B-1",new ParkingSpot("B-1", VehicleSize.SMALL));
        parkingSpotMap.putIfAbsent("B-2",new ParkingSpot("B-2", VehicleSize.MEDIUM));
        parkingSpotMap.putIfAbsent("B-3",new ParkingSpot("B-3", VehicleSize.LARGE));
        ParkingFloor parkingFloor2 = new ParkingFloor("2",parkingSpotMap);
        parkingLot.addFloor(parkingFloor1);
        parkingLot.addFloor(parkingFloor2);

        Vehicle vehicle1 = new Vehicle("UP803948",VehicleSize.LARGE);
        Vehicle vehicle2 = new Vehicle("UP803edf8",VehicleSize.MEDIUM);
        Vehicle vehicle3 = new Vehicle("UP8efdd948",VehicleSize.SMALL);
        Vehicle vehicle4 = new Vehicle("UP80def8",VehicleSize.SMALL);
        Optional<Ticket> t1 = parkingLot.parkVehicle(vehicle1);
        Optional<Ticket> t2 = parkingLot.parkVehicle(vehicle2);
        Optional<Ticket> t3 = parkingLot.parkVehicle(vehicle3);
        Optional<Double> fee4 = parkingLot.unparkVehicle(vehicle4.getLicensePlate());
        Optional<Ticket> t4 = parkingLot.parkVehicle(vehicle4);
        Optional<Double> fee1 = parkingLot.unparkVehicle(vehicle1.getLicensePlate());
        Optional<Double> fee2 = parkingLot.unparkVehicle(vehicle2.getLicensePlate());
        Optional<Double> fee3 = parkingLot.unparkVehicle(vehicle3.getLicensePlate());

    }
}