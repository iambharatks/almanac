package Entities.Vehicle;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public final class Vehicle {
    private String licensePlate;
    private VehicleSize vehicleSize;
}
