package Entities.Seat;

public record SeatState(SeatStatus status, String heldby, long expiryMillis) {
    static SeatState available(){
        return new SeatState(SeatStatus.AVAILABLE,null,0L);
    }
    static SeatState heldby(String userId, long expiryMillis){
        return new SeatState(SeatStatus.HELD,userId,expiryMillis);
    }
    public SeatState booked(){
        return new SeatState(SeatStatus.BOOKED,this.heldby,0L);
    }
    public String toString(){
        return status.toString()+" "+heldby+ " " +  expiryMillis;
    }
}
