package Entities;

import Entities.Seat.Seat;
import PaymentStrategy.PaymentStrategy;
import PriceCalculationStrategy.PriceCalculationStrategy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Venue {
    final String name;
    final String city;
    final Map<String,Theatre> theatres;
    final Map<String,Show> shows;
    final PriceCalculationStrategy priceStrategy;
    final PaymentStrategy paymentStrategy;

    public Venue(String name, String city, List<Theatre> theatreList, PriceCalculationStrategy priceStrategy, PaymentStrategy paymentStrategy){
        this.name = name;
        this.city = city;
        this.theatres = theatreList.stream().collect(Collectors.toMap(Theatre::getTheatreId,theatre -> theatre));
        this.shows = new ConcurrentHashMap<>();
        this.priceStrategy = priceStrategy;
        this.paymentStrategy = paymentStrategy;
    }

    public Optional<Show> addShow(String theatreId, String showName, TimeSlot timeSlot){
        Theatre theatre = theatres.get(theatreId);
        if(theatre == null){
            System.out.println("No such theatre");
            return Optional.empty();
        }
        String showId = UUID.randomUUID().toString();
        try {
            Optional<Show> show = theatres.get(theatreId).addShow(showId,showName, timeSlot);
            shows.put(showId,show.get());
            return show;
        }catch (Exception e){
            e.printStackTrace();
            return Optional.empty();
        }
    }
    public Optional<List<String>> getSeatsForShow(String showId){
        Show show =  shows.get(showId);
        if(show == null) {
            System.out.println("No such show");
            return Optional.empty();
        }
        return Optional.of(show.getAvailableSeats(System.currentTimeMillis()));
    }
    public Optional<Ticket> bookSeats(String showId, List<String> seatIds,String userId, long now){
        Show show = shows.get(showId);
        if(show == null){
            System.out.println("No such show");
            return Optional.empty();
        }
        Optional<List<Seat>> acquiredSeats = show.holdSeats(seatIds,userId,now);
        if(acquiredSeats.isEmpty()){
            System.out.println("Failed to hold seats");
            return Optional.empty();
        }
        double amount = priceStrategy.calculate(show,seatIds);
        Payment paymentResult = paymentStrategy.pay( amount,userId);
        if(!paymentResult.paymentResult().equals(PaymentResult.SUCCESS)){
            show.releaseSeats(seatIds, userId, System.currentTimeMillis());
            System.out.println("Payment failed");
            return Optional.empty();
        }
        Optional<Ticket> ticket = show.confirmSeats(acquiredSeats.get(),userId,System.currentTimeMillis());
        if(ticket.isEmpty()) {
            System.out.println("Failed to confirm seats");
            paymentStrategy.refund(paymentResult.txnId());
        }
        return ticket;
    }
}
