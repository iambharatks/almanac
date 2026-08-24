package Entities.RateLimitStrategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class FixedWindowStrategy implements  RateLimiterStrategy{
    private final Map<String, AtomicLong> counters;
    private final long windowSize;
    private final int limit;
    private final ScheduledExecutorService sweeper;

    public FixedWindowStrategy(long windowSize, int limit) {
        this.windowSize = windowSize;
        this.counters = new ConcurrentHashMap<>();
        this.limit = limit;
        sweeper = Executors.newSingleThreadScheduledExecutor(
                runnable ->{
                    Thread thread = new Thread(runnable, "fixedwindow-ratelimit-sweeper");
                    thread.setDaemon(true);
                    return thread;
                }
        );
        sweeper.scheduleAtFixedRate(this::evictStaleResources, windowSize, windowSize, TimeUnit.MILLISECONDS);
    }

    private void evictStaleResources(){
        long currentWindow = System.currentTimeMillis()/windowSize;
        counters.keySet().removeIf(key -> {
            long w = Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
            System.out.println("Removing the following "+key+" from the map");
            return w < currentWindow;
        });
    }

    @Override
    public boolean isAllowed(String resourceId) {
        long windowId = System.currentTimeMillis()/windowSize;
        String key = resourceId+":"+windowId;
        AtomicLong counter =  counters.computeIfAbsent(key,k-> new AtomicLong(0));
//        counters.remove(resourceId+":"+(windowId-1));
        while(true){
            long current = counter.get();
            if(current >= limit){
                System.out.println(resourceId+" not allowed");
                return false;
            }
            if(counter.compareAndSet(current,current+1)){
                System.out.println(resourceId+" allowed" );
                return true;
            }
        }
    }
}
