package Entities.FeeCalculation;

import Entities.Ticket;
import Entities.Vehicle.VehicleSize;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

public final class SizeBasedFeeCalculation implements FeeCalculationStrategy{
    public final Map<VehicleSize,Double> HOURLY_RATES;
    public final Double MIN_FEE = 10.0;
    public SizeBasedFeeCalculation(Map<VehicleSize,Double> hourlyRates){
        this.HOURLY_RATES = Map.copyOf(hourlyRates) ;
    }
    @Override
    public Double calculateFee(Ticket ticket) {
        if(ticket.getEntryTime().isBefore(ticket.getExitTime())){
            long duration = Duration.between(ticket.getExitTime(), LocalDateTime.now()).toHours();
            return Math.max(duration*HOURLY_RATES.getOrDefault(ticket.getVehicle().getVehicleSize(),0.0),MIN_FEE);
        }
        System.out.println("Invalid time captured!\nTicket is malformed.\n");
        return 0.0;
    }
}
