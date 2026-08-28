package Entities;

import java.time.LocalDateTime;

public record TimeSlot(LocalDateTime start, LocalDateTime end) implements Comparable<TimeSlot> {

    @Override
    public int compareTo(TimeSlot timeSlot) {
        int cmp = start.compareTo(timeSlot.start);
        return (cmp != 0)?cmp: end.compareTo(timeSlot.end);
    }

    public boolean overlaps(TimeSlot timeSlot){
        if(this.end.isBefore(timeSlot.start) || this.start.isAfter(timeSlot.end()))
            return false;
        return true;
    }
}
