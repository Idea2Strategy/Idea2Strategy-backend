package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.usercase.UserCaseView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record OperatorCaseState(
        UserCaseView caseView,
        UUID assigneeOperatorId,
        List<Evidence> evidence,
        String subject,
        String description,
        Instant databaseNow,
        Instant responseDeadlineAt,
        String deadlinePolicyVersion) {
    public OperatorCaseState {
        Objects.requireNonNull(caseView, "caseView");
        evidence = List.copyOf(evidence);
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(databaseNow, "databaseNow");
        if ((responseDeadlineAt == null) != (deadlinePolicyVersion == null)) {
            throw new IllegalArgumentException("CASE_DEADLINE_PAIR_INVALID");
        }
    }

    public OperatorCaseState(UserCaseView caseView, UUID assigneeOperatorId, List<Evidence> evidence) {
        this(caseView, assigneeOperatorId, evidence, "", "", caseView.updatedAt(), null, null);
    }

    public OperatorCaseState(
            UserCaseView caseView,
            UUID assigneeOperatorId,
            List<Evidence> evidence,
            Instant databaseNow,
            Instant responseDeadlineAt,
            String deadlinePolicyVersion) {
        this(caseView, assigneeOperatorId, evidence, "", "", databaseNow,
                responseDeadlineAt, deadlinePolicyVersion);
    }

    public record Evidence(
            UUID evidenceId,
            String kind,
            String status,
            String sourceDomain,
            boolean ownershipVerified,
            Instant linkedAt,
            Map<String, Object> attributes) {
        public Evidence {
            Objects.requireNonNull(evidenceId, "evidenceId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(sourceDomain, "sourceDomain");
            Objects.requireNonNull(linkedAt, "linkedAt");
            attributes = Map.copyOf(attributes);
        }
    }
}
