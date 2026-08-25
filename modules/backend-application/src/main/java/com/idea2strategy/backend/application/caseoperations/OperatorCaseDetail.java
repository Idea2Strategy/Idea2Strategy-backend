package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OperatorCaseDetail(
        UUID caseId,
        UserCaseType type,
        UserCaseStatus status,
        long version,
        UUID assigneeOperatorId,
        String subject,
        String description,
        List<OperatorEvidenceView> evidence,
        Instant updatedAt) {
    public OperatorCaseDetail {
        evidence = List.copyOf(evidence);
    }
}
