package com.idea2strategy.backend.application.competition;

public final class InvalidScoringTemplateException extends IllegalArgumentException {
    public InvalidScoringTemplateException(String message) {
        super(message);
    }

    public InvalidScoringTemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
