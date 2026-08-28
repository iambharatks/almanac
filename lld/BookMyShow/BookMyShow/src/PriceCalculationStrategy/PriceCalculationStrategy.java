package PriceCalculationStrategy;

import Entities.Show;

import java.util.List;

public interface PriceCalculationStrategy {
    public double calculate(Show show, List<String> seatIds);
}
