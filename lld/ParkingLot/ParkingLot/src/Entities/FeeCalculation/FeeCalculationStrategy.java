package Entities.FeeCalculation;

import Entities.Ticket;

public interface FeeCalculationStrategy {
    Double calculateFee(Ticket ticket);
}
