package BookingStrategy;

import Entities.Show;

import java.util.List;

public class AutoSeatSelection implements SeatSelectionStrategy {
    @Override
    public List<String> selectSeats(Show show,List<String> seatIds,long now) {
        List<String> free = show.getAvailableSeats(now);
        if(free.size() < seatIds.size()) {
            return List.of();
        }
        return free.subList(0,seatIds.size());
    }
}
