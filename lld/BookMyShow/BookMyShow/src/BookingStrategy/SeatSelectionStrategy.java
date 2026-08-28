package BookingStrategy;

import Entities.Show;
import Entities.Ticket;

import java.util.List;
import java.util.Optional;

public interface SeatSelectionStrategy {
    public List<String> selectSeats(Show show, List<String> seatIds, long now);
}
