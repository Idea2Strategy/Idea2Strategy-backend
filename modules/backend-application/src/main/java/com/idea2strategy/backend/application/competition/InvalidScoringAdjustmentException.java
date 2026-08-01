package com.idea2strategy.backend.application.competition;

public final class InvalidScoringAdjustmentException extends IllegalArgumentException {
    public InvalidScoringAdjustmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
