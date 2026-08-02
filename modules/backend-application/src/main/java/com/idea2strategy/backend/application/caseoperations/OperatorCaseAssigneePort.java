package com.idea2strategy.backend.application.caseoperations;

import java.time.Instant;
import java.util.UUID;

public interface OperatorCaseAssigneePort {
    boolean isActiveAssignableOperator(UUID operatorId, Instant evaluatedAt);
}
