import Entities.RateLimitStrategy.FixedWindowStrategy;
import Entities.RateLimitStrategy.RateLimiterStrategy;
import Entities.RateLimitStrategy.TokenBucketStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) {
//        RateLimiterStrategy strategy = new FixedWindowStrategy(10,  1);
//        RateLimiterStrategy tokenBucketStrategy = new TokenBucketStrategy(1,  10000);
//        RateLimiter rateLimiter = new RateLimiter(tokenBucketStrategy);
//        List<String> resourceIds = new ArrayList<>();
//        for(int i = 0; i < 10; i++){
//            resourceIds.add(UUID.randomUUID().toString());
//        }
//        resourceIds.forEach(rateLimiter::isAllowed);
//        resourceIds.forEach(rateLimiter::isAllowed);
//        resourceIds.forEach(rateLimiter::isAllowed);

        var tokenBucketStrategy1 = new TokenBucketStrategy(5, 2);   // capacity 5, 2 tokens/sec
        var limiter = new RateLimiter(tokenBucketStrategy1);
        String id = "user-1";                            // ONE resource

        for (int i = 0; i < 10; i++) System.out.println(i + ": " + limiter.isAllowed(id));
// expect: first 5 true, next 5 false

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < 10; i++) System.out.println(i + ": " + limiter.isAllowed(id));
// expect: ~2 true (one second of refill), rest false
    }
}