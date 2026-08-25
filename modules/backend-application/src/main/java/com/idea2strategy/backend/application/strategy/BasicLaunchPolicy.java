package com.idea2strategy.backend.application.strategy;

/** Server-owned launch behavior for BASIC strategies. */
public final class BasicLaunchPolicy {
    public static final String CANDIDATE_CONFLICT_POLICY = "{\"policy\":\"FIRST_WINS\"}";

    private BasicLaunchPolicy() {}
}
