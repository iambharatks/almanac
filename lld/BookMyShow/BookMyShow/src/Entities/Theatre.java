package Entities;

import Entities.Seat.Seat;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class Theatre {
    private final String theatreId;
    private final List<Seat> seatList;
    private final TreeMap<TimeSlot,Show> schedule;
    public Theatre(String theatreId, List<Seat> seatList) {
        this.theatreId = theatreId;
        //can't be added removed but can be mutated though not wanted
        this.seatList = List.copyOf(seatList);
        this.schedule = new TreeMap<>();
    }
    public String getTheatreId() {
        return theatreId;
    }
    public List<Seat> getSeatList() {
        return seatList;
    }
    public synchronized  Optional<Show> addShow(String showId, String showName, TimeSlot timeSlot){
        TimeSlot prev = schedule.floorKey(timeSlot);
        TimeSlot next = schedule.ceilingKey(timeSlot);
        if(prev != null && prev.overlaps(timeSlot)){
            throw new ShowConflictException(prev);
        }
        if(next != null && next.overlaps(timeSlot)){
            throw new ShowConflictException(next);
        }
        Show show = new Show(showId, showName, this, timeSlot);
        schedule.put(timeSlot, show);
        return Optional.of(show);
    }
}
