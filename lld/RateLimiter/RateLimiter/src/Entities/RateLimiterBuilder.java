package Entities;

import Entities.RateLimitStrategy.FixedWindowStrategy;
import Entities.RateLimitStrategy.RateLimiterStrategy;
import Entities.RateLimitStrategy.TokenBucketStrategy;

//! BUILDER NOT GOOD FOR THIS EXAMPLE AS IT SHOULD ADD TOTALLY DISJOINT PARAMETERS OF A OBJECT HERE IT OVERRIDES ONE ANOTHER.
//! NOT GOOD DESIGN PATTERN USECASE
//! IMPLEMENTED BUILDER FOR PRACTICE
public class RateLimiterBuilder {
    private final RateLimiterStrategy strategy;

    private RateLimiterBuilder(RateLimiterStrategy strategy){
        this.strategy = strategy;
    }

    public boolean isAllowed(String resourceId){
        return  strategy.isAllowed(resourceId);
    }

    public static Builder builder(){
        return new Builder();
    }

    public static class Builder{
        private RateLimiterStrategy strategy;

        public Builder tokenBucket(long capacity, double refillRate){
            this.strategy = new TokenBucketStrategy(capacity, refillRate);
            return this;
        }
        public Builder fixedWindow(long windowSize, int limit){
            this.strategy = new FixedWindowStrategy(windowSize,limit);
            return this;
        }
        public RateLimiterBuilder build(){
            if(strategy == null){
                throw new  IllegalArgumentException("Strategy can't be null.");
            }
            return new RateLimiterBuilder(strategy);
        }
    };

}
