import Entities.RateLimitStrategy.RateLimiterStrategy;

public final class RateLimiter {
    private final RateLimiterStrategy rateLimiterStrategy;

    public RateLimiter(RateLimiterStrategy rateLimiterStrategy) {
        this.rateLimiterStrategy = rateLimiterStrategy;
    }

    public boolean isAllowed(String resourceId){
        return rateLimiterStrategy.isAllowed(resourceId);
    }

}
