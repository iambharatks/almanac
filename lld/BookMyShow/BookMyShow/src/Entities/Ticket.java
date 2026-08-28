package Entities;

import Entities.Seat.Seat;

import java.util.List;

public record Ticket(String ticketId, String showID, TimeSlot timeSlot, String theatreID, List<String> bookedSeats) {

}
