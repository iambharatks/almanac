package Entities.RateLimitStrategy;

public interface RateLimiterStrategy {
    public boolean isAllowed(String resourceId);
}
