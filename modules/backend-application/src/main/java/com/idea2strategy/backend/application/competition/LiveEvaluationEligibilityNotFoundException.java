package com.idea2strategy.backend.application.competition;

public final class LiveEvaluationEligibilityNotFoundException extends RuntimeException {
    public LiveEvaluationEligibilityNotFoundException() {
        super("Live evaluation participation was not found");
    }
}
