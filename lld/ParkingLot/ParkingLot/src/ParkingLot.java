import Entities.*;
import Entities.FeeCalculation.FeeCalculationStrategy;
import Entities.FeeCalculation.SizeBasedFeeCalculation;
import Entities.SpotAllocation.NearestFirstStrategy;
import Entities.SpotAllocation.ParkingSpotAllocationStrategy;
import Entities.Vehicle.Vehicle;
import Entities.Vehicle.VehicleSize;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ParkingLot {
    private final static Double PENALTY = 777.0;
    private List<ParkingFloor> floors;
    private Map<String,Ticket> activeTickets;
    private final ParkingSpotAllocationStrategy parkingStrategy;
    private final FeeCalculationStrategy feeStrategy;
    //NOT USED NOW WITH BILL PUGH SINGLETON IMPLEMENTATION
    // private volatile static ParkingLot parkingLot;

    private ParkingLot() {
        floors = new ArrayList<>();
        final Map<VehicleSize, Double> HOURLY_RATES = Map.of(
                VehicleSize.SMALL, 10.0,
                VehicleSize.MEDIUM, 20.0,
                VehicleSize.LARGE, 30.0
        );
        activeTickets = new ConcurrentHashMap<>();

        this.feeStrategy = new SizeBasedFeeCalculation(HOURLY_RATES);
        this.parkingStrategy = new NearestFirstStrategy();
    }
    private static class HOLDER{
        static final ParkingLot INSTANCE = new ParkingLot();
        static{
            System.out.println("ParkingLot instance created");
        }
    }
    public static ParkingLot getInstance(){
        //INSTANCE created when 
        return HOLDER.INSTANCE;
        // if(parkingLot == null){
        //     synchronized (ParkingLot.class){
        //         if(parkingLot == null){
        //             parkingLot = new ParkingLot();
        //         }
        //     }
        // }
        // return parkingLot;
    }

    public void addFloor(ParkingFloor floor) {
        floors.add(floor);
    }

    public Optional<Ticket> parkVehicle(Vehicle vehicle){
        synchronized(this){
            Optional<ParkingSpot> availableSpot = parkingStrategy.findParkingSpot(floors,vehicle);
            if(availableSpot.isPresent()){
                ParkingSpot parkingSpot = availableSpot.get();
                if(parkingSpot.parkVehicle(vehicle)){
                    Ticket ticket = new Ticket(parkingSpot,vehicle);
                    activeTickets.put(vehicle.getLicensePlate(),ticket);
                    System.out.printf("%s has been parked at %s.\n Ticket: %s\n",vehicle.getLicensePlate(),parkingSpot.getSpotId(),ticket.getTicketId());
                    return Optional.of(ticket);
                }
            }
        }
       
        System.out.println("Some error occurred!\nPlease try again...\n");
        return Optional.empty();
    }

    public Optional<Double> unparkVehicle(String licenseNumber){
        Ticket ticket;
        ticket = activeTickets.remove(licenseNumber);
        synchronized(this){
        }
            if(ticket == null){
                System.out.println("Ticket Not Found\n Be ready for penalty");
                return Optional.of(PENALTY);
            }
            ticket.setExitTime(LocalDateTime.now());
        ticket.getParkingSpot().unparkVehicle(ticket.getVehicle());
        Double fees = feeStrategy.calculateFee(ticket);
        System.out.println("Please pay your fees!\nFees: " + fees);
        return Optional.of(fees);
    }
}
