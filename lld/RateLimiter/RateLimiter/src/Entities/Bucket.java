package Entities;

public final class Bucket {
    private double tokenCounter;
    private long lastRefillMillis;

    public Bucket(long tokenCounter,long lastRefillMillis) {
        this.tokenCounter = tokenCounter;
        this.lastRefillMillis = lastRefillMillis;
    }
    public synchronized boolean tryConsume(long now, double refillRate, double capacity){
        double elapsed = (now-lastRefillMillis)/1000.0;
        tokenCounter  = Math.min(capacity,tokenCounter+elapsed*refillRate);
        lastRefillMillis = now;
        if(tokenCounter < 1){
            return false;
        }
        tokenCounter -= 1;
        return true;
    }
    public synchronized boolean isIdle(long now, long threshold){
        return now-lastRefillMillis > threshold;
    }

}
