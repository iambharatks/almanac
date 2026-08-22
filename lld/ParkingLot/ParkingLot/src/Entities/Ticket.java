package Entities;

import Entities.Vehicle.Vehicle;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;


@Data
public class Ticket {
    private final String ticketId;
    private final ParkingSpot parkingSpot;
    private final Vehicle vehicle;
    private final LocalDateTime entryTime;
    private LocalDateTime exitTime = null;

    public Ticket(ParkingSpot parkingSpot, Vehicle vehicle) {
        this.ticketId = UUID.randomUUID().toString();
        this.parkingSpot = parkingSpot;
        this.vehicle = vehicle;
        this.entryTime = LocalDateTime.now();
    }

}
