package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OperatorCaseDecisionResult(
        Status status,
        String code,
        Mutation mutation,
        AuditEvidence auditEvidence) {
    public OperatorCaseDecisionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(auditEvidence, "auditEvidence");
        if (status == Status.APPLIED && mutation == null) {
            throw new IllegalArgumentException("applied result requires mutation");
        }
        if (status != Status.APPLIED && mutation != null) {
            throw new IllegalArgumentException("non-applied result cannot contain mutation");
        }
    }

    public enum Status {
        APPLIED,
        NO_OP,
        REJECTED
    }

    public record Mutation(
            UUID assigneeOperatorId,
            UserCaseStatus status,
            long nextVersion,
            String eventType,
            String sanctionResultReference,
            Instant responseDeadlineAt,
            String deadlinePolicyVersion) {
        public Mutation(UUID assigneeOperatorId, UserCaseStatus status, long nextVersion,
                String eventType, String sanctionResultReference) {
            this(assigneeOperatorId, status, nextVersion, eventType, sanctionResultReference, null, null);
        }
    }

    public record AuditEvidence(
            UUID actorOperatorId,
            UUID caseId,
            String action,
            String reasonCode,
            UUID correlationId,
            String rbacCatalogVersion,
            long beforeVersion,
            long afterVersion,
            UUID beforeAssigneeOperatorId,
            UUID afterAssigneeOperatorId,
            UserCaseStatus beforeStatus,
            UserCaseStatus afterStatus,
            List<OperatorEvidenceView> evidence,
            String sanctionResultReference,
            Instant evaluatedAt) {
        public AuditEvidence {
            evidence = List.copyOf(evidence);
        }
    }
}
