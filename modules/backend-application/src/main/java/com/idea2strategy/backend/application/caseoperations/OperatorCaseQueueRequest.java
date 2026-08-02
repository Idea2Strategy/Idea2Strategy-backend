package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OperatorCaseQueueRequest(
        OperatorRequestContext requestContext,
        UUID requiredPermissionId,
        Set<UserCaseType> caseTypes,
        Set<UserCaseStatus> statuses,
        UUID assigneeOperatorId,
        String cursor,
        int limit) {
    public OperatorCaseQueueRequest {
        Objects.requireNonNull(requestContext, "requestContext");
        Objects.requireNonNull(requiredPermissionId, "requiredPermissionId");
        caseTypes = Set.copyOf(caseTypes);
        statuses = Set.copyOf(statuses);
        if (caseTypes.isEmpty()) {
            throw new IllegalArgumentException("at least one case type is required");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }
}
