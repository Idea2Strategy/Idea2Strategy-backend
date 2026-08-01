package com.idea2strategy.backend.application.strategy;

@FunctionalInterface
public interface StrategyEditLeaseTokenGenerator {
    String nextToken();
}
