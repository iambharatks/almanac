package Entities.Seat;

import java.util.concurrent.atomic.AtomicReference;

public final class Seat {
    private final String seatId;
    private final AtomicReference<SeatState> state;
    public Seat(String seatId) {
        this.seatId = seatId;
        state = new AtomicReference<>(SeatState.available());
    }
    public String getSeatId() {
        return seatId;
    }
    public boolean isAvailable(long now) {
        SeatState state = this.state.get();
        return state.status() == SeatStatus.AVAILABLE || (state.status() == SeatStatus.HELD && now >= state.expiryMillis());
    }

    public boolean tryHold(String userId,long now, long ttlMillis) {
        while(true){
            SeatState cur = state.get();
            System.out.println("Trying to hold this seat with following state "+cur);
            if(!this.isAvailable(now)) return false;
            SeatState heldState = SeatState.heldby(userId,now+ttlMillis);
            if(state.compareAndSet(cur,heldState)){
                return true;
            }
        }
    }

    public boolean releaseHeld(String userId){
        while(true){
            SeatState curState = state.get();
            System.out.println("Trying to release seat "+seatId+" from user "+userId+" with current state "+curState);
            if(curState.status() != SeatStatus.HELD || !userId.equals(curState.heldby())){
                System.out.println("Cannot release seat as its state is this "+curState);
                return false;
            }
            if(state.compareAndSet(curState,SeatState.available())){
                System.out.println("Successfully released seat "+seatId+" for user "+userId+" with new state "+state.get());
                return true;
            }
        }
    }

    public boolean isHeldby(String userId, long now){
        SeatState curState = this.state.get();
        return curState.status() == SeatStatus.HELD && curState.heldby().equals(userId) && curState.expiryMillis() > now;
    }

    public boolean releaseBooked(String userId){
        while(true){
            SeatState curState = state.get();
            System.out.println("Trying to unbook seat "+seatId+" from user "+userId+" with current state "+curState);
            if(curState.status() != SeatStatus.BOOKED || !userId.equals(curState.heldby())){
                System.out.println("Cannot unbook seat as its state is this "+curState);
                return false;
            }
            if(state.compareAndSet(curState,SeatState.available())){
                System.out.println("Successfully unbooked seat "+seatId+" for user "+userId+" with current state "+curState);
                return true;
            }
        }
    }

    public boolean confirmSeat(String userId, long now){
        while(true){
            SeatState curState = state.get();
            if(curState.status() != SeatStatus.HELD || !userId.equals(curState.heldby()) || curState.expiryMillis() < now){
                return false;
            }
            if(state.compareAndSet(curState,curState.booked())){
                return true;
            }
        }
    }
}
