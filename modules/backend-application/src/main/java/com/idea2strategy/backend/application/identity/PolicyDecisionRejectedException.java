package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public final class PolicyDecisionRejectedException extends RuntimeException {
    private final ConsentDecisionOutcome outcome;

    public PolicyDecisionRejectedException(ConsentDecisionOutcome outcome) {
        super("Policy consent decision was rejected: " + Objects.requireNonNull(outcome, "outcome"));
        this.outcome = outcome;
    }

    public ConsentDecisionOutcome outcome() {
        return outcome;
    }
}
