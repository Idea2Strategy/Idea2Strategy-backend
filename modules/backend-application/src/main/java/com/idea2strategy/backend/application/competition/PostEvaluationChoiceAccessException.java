package com.idea2strategy.backend.application.competition;

public final class PostEvaluationChoiceAccessException extends RuntimeException {
    public PostEvaluationChoiceAccessException() {
        super("Post-evaluation choice is not accessible");
    }
}
