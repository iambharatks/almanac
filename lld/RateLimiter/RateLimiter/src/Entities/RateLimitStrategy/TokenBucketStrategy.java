package Entities.RateLimitStrategy;

import Entities.Bucket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenBucketStrategy implements RateLimiterStrategy{
    private final Map<String, Bucket> counter;
    private final long capacity;
    private final double refillRate;

    public  TokenBucketStrategy(long capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.counter = new ConcurrentHashMap<>();
    }

    @Override
    public boolean isAllowed(String resourceId) {
        Bucket bucket = counter.computeIfAbsent(resourceId,k->new Bucket(capacity,0));
        if(bucket.tryConsume(System.currentTimeMillis(),refillRate,capacity)){
            System.out.println("TokenBucketStrategy isAllowed for " + resourceId);
            return true;
        }
        System.out.println("TokenBucketStrategy is Not Allowed for " + resourceId);
        return false;
    }
}
