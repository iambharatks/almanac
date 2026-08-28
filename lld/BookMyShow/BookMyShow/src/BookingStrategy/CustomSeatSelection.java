package BookingStrategy;

import Entities.Show;
import Entities.Ticket;

import java.util.List;
import java.util.Optional;

public class CustomSeatSelection implements SeatSelectionStrategy {

    @Override
    public List<String> selectSeats(Show show, List<String> seatIds, long now ) {
        return seatIds;
    }
}
