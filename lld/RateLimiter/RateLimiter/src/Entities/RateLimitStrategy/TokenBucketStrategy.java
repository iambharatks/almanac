package Entities.RateLimitStrategy;

import Entities.Bucket;

import java.util.Map;
import java.util.concurrent.*;

public class TokenBucketStrategy implements RateLimiterStrategy{
    private final Map<String, Bucket> counter;
    private final long capacity;
    private final double refillRate;
    private final long threshold;
    //eviction done now
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "tokenbucket-ratelimit-sweeper");
                thread.setDaemon(true);
                return thread;
            }
    );;

    public TokenBucketStrategy(long capacity, double refillRate, long threshold) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.counter = new ConcurrentHashMap<>();
        this.threshold = threshold;
        sweeper.scheduleAtFixedRate(this::evictStaleResources,threshold,threshold, TimeUnit.MILLISECONDS);
    }

    public  TokenBucketStrategy(long capacity, double refillRate) {
        this(capacity, refillRate, 1000);
    }

    @Override
    public boolean isAllowed(String resourceId) {
        Bucket bucket = counter.computeIfAbsent(resourceId, k -> new Bucket(capacity, 0));
        if (bucket.tryConsume(System.currentTimeMillis(), refillRate, capacity)) {
            return true;
        }
        return false;
    }

    private void evictStaleResources(){
        counter.entrySet().removeIf(e->{
            Bucket bucket = e.getValue();
            boolean isRemoved = bucket.isIdle(System.currentTimeMillis(), threshold);
            if(isRemoved){
                System.out.println("idle resource evicted from map"+e.getKey());
            }
            return isRemoved;
        });
    }
}
