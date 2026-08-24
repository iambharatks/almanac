import Entities.RateLimitStrategy.TokenBucketStrategy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrencyTest {
    public static void main(String[] args) throws Exception {
        final int THREADS = 100, LIMIT = 10;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(THREADS);
        ExecutorService pool     = Executors.newFixedThreadPool(THREADS);
        AtomicInteger allowed    = new AtomicInteger();
        AtomicInteger rejected   = new AtomicInteger();
        RateLimiter rateLimiter = new RateLimiter(new TokenBucketStrategy(LIMIT,2));
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();                       // fire together
                    if (rateLimiter.isAllowed("user-1")) allowed.incrementAndGet();
                    else rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        done.await();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println(allowed.get() == LIMIT
                ? "PASS: exactly " + LIMIT + " allowed"
                : "FAIL: expected " + LIMIT + ", got " + allowed.get());
    }
}