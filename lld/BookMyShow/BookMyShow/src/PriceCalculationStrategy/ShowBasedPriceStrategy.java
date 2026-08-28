package PriceCalculationStrategy;

import Entities.Show;
import Entities.TimeSlot;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;

public class ShowBasedPriceStrategy implements PriceCalculationStrategy {
    private final double basePrice;
    private final double weekendMultiplier;
    private final double primeTimeMultiplier;

    public ShowBasedPriceStrategy(double basePrice, double weekendMultiplier, double primeTimeMultiplier){
        this.basePrice = basePrice;
        this.weekendMultiplier = weekendMultiplier;
        this.primeTimeMultiplier = primeTimeMultiplier;
    }

    @Override
    public double calculate(Show show, List<String> seatIds) {
        TimeSlot timeSlot = show.getTimeSlot();
        LocalDateTime start = timeSlot.start();
        LocalDateTime end = timeSlot.end();
        DayOfWeek day = start.getDayOfWeek();
        double price = basePrice* show.getPriceMultiplier()*seatIds.size();
        if(day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY){
            return price * weekendMultiplier;
        }
        int hour = start.getHour();
        if(hour >= 18 && hour <= 22){
            return price * primeTimeMultiplier;
        }
        return basePrice;
    }
}
