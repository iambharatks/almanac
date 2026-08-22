package Entities.FeeCalculation;

import Entities.Ticket;

import java.time.Duration;

public final class FlatRateFeeCalculation implements FeeCalculationStrategy{

    private final Double HOURLY_RATES;

    FlatRateFeeCalculation(Double hourlyRates){
        this.HOURLY_RATES = hourlyRates;
    }

    @Override
    public Double calculateFee(Ticket ticket) {
        if(ticket.getEntryTime().isBefore(ticket.getExitTime())){
            long duration = Duration.between(ticket.getEntryTime(),ticket.getExitTime()).toHours();
            return HOURLY_RATES * duration;
        }
        System.out.println("Invalid time captured!\n Ticket is malformed.\n");
        return 0.0;
    }
}
