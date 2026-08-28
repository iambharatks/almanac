package Entities;

import Entities.Seat.Seat;

import javax.swing.text.html.Option;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public final class Show {
    private final String showName;
    private final String showId;
    private final String theatreId;
    private final TimeSlot timeSlot;
    private final Map<String, Seat> seats;
    private final Map<String,Ticket> activeTickets;
    private final long HOLD_TTL = 1000;
    private final double priceMultiplier;

    public Show(String showName, String showId, Theatre theatre, TimeSlot timeSlot, double priceMultiplier){
        this.showName = showName;
        this.showId = showId;
        this.theatreId = theatre.getTheatreId();
        this.timeSlot = timeSlot;
        Map<String, Seat> seats = new HashMap<>();
        for(Seat seat : theatre.getSeatList()) {
            seats.put(seat.getSeatId(),new Seat(seat.getSeatId()));
        }
        //avoid other from adding removing elements but can mutate existing ones.
        this.seats = Map.copyOf(seats);
        this.activeTickets = new ConcurrentHashMap<>();
        this.priceMultiplier = priceMultiplier;
    }

    public Show(String showId, String showName, Theatre theatre, TimeSlot timeSlot) {
        this(showName,showId,theatre,timeSlot,1.0);
    }
    public String getShowId(){
        return showId;
    }
    public String getShowName(){
        return showName;
    }
    public List<String> getAvailableSeats(long now) {
        return seats.values().stream().filter(seat -> seat.isAvailable(now)).map(Seat::getSeatId).toList();
    }
    public Optional<List<Seat>> holdSeats(List<String> seatIds, String userId, long now){
        List<String> sorted = seatIds.stream().sorted().toList();
        List<Seat> acquired = new ArrayList<>();
        for(String seatId : sorted){
            Seat seat = seats.get(seatId);
            if(seat == null) {
                System.out.println("No such seat");
                rollback(acquired,userId,Seat::releaseHeld);
                return Optional.empty();
            }
            if(seat.tryHold(userId,now,HOLD_TTL)){
                System.out.println("Acquired seat " + seat.getSeatId()+" at "+System.currentTimeMillis());
                acquired.add(seat);
            }else{
                rollback(acquired,userId,Seat::releaseHeld);
                return Optional.empty();
            }
        }
        return Optional.of(acquired);
    }

    public void releaseSeats(List<String> seatIds, String userId, long now){
        List<Seat> acquired = seatIds.stream().map(seats::get).toList();
        rollback(acquired,userId,Seat::releaseHeld);
    }

    public void rollback(List<Seat> acquired, String userId, BiConsumer<Seat,String> rollbackAction) {
        System.out.println("Rolling back seats");
        acquired.forEach(seat ->  rollbackAction.accept(seat,userId));
    }

    public TimeSlot getTimeSlot() {
        return timeSlot;
    }
    public double getPriceMultiplier() {
        return priceMultiplier;
    }

    public synchronized Optional<Ticket>  confirmSeats(List<Seat> acquiredSeat, String userId, long now){
//        List<Seat> bookedSeats = new ArrayList<>();
//        for(Seat seat : acquiredSeat){
//            if(seat.confirmSeat(userId,now)){
//                bookedSeats.add(seat);
//            }else{
//                rollback(bookedSeats,userId,Seat::releaseBooked);
//                rollback(acquiredSeat.subList(bookedSeats.size(),acquiredSeat.size()),userId,Seat::releaseHeld);
//                return Optional.empty();
//            }
//        }
//        Ticket ticket = new Ticket(UUID.randomUUID().toString(),showId,timeSlot,theatreId,bookedSeats.stream().map(Seat::getSeatId).toList());
//        activeTickets.put(ticket.ticketId(),ticket);
//        return Optional.of(ticket);
        //INSTEAD OF ABOVE USE THIS
        //IMPLEMENTING ALL OR NOTHING FOR BOOKING
        //PHASE -1  HOLD
        for(Seat seat : acquiredSeat){
            if(!seat.isHeldby(userId,now)){
                rollback(acquiredSeat,userId,Seat::releaseHeld);
                return Optional.empty();
            }
        }
        //PHASE -2 COMMIT
        for(Seat seat : acquiredSeat){
            seat.confirmSeat(userId,now);
        }
        Ticket ticket = new Ticket(UUID.randomUUID().toString(),showId,timeSlot,theatreId,acquiredSeat.stream().map(Seat::getSeatId).toList());
        activeTickets.put(ticket.ticketId(),ticket);
        return Optional.of(ticket);
    }
}
