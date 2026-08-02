package com.idea2strategy.backend.application.caseoperations;

import java.time.Instant;

public interface OperatorCaseCommandPort {
    OperatorCaseDecisionResult executeAtomically(
            OperatorCaseCommand command,
            Instant evaluatedAt,
            Decision decision);

    @FunctionalInterface
    interface Decision {
        OperatorCaseDecisionResult decide(OperatorCaseState state);
    }
}
