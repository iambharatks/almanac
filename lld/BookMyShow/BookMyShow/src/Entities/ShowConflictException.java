package Entities;

public class ShowConflictException extends RuntimeException {
    public ShowConflictException(TimeSlot prev) {
            super("Theatre already booked for "+ prev);
    }
}
