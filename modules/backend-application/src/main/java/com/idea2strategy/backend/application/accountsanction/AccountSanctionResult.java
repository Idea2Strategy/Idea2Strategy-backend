package com.idea2strategy.backend.application.accountsanction;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AccountSanctionResult(
        Status status,
        String code,
        Mutation mutation,
        AccountSanctionAuthorizationPort.Decision authorization,
        AccountAccessRevocationPort.Effect accessRevocation,
        List<AccountSanctionOutboxPublicationPort.Message> outboxMessages) {

    public AccountSanctionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(authorization, "authorization");
        outboxMessages = List.copyOf(outboxMessages);
        if (status == Status.APPLIED && mutation == null) {
            throw new IllegalArgumentException("applied results require a mutation");
        }
        if (status != Status.APPLIED && (mutation != null || accessRevocation != null || !outboxMessages.isEmpty())) {
            throw new IllegalArgumentException("non-applied results cannot publish effects");
        }
    }

    public record Mutation(
            Kind kind,
            UUID accountId,
            UUID sanctionId,
            AccountSanctionState.Type sanctionType,
            AccountSanctionState.Status beforeStatus,
            AccountSanctionState.Status afterStatus,
            String sanctionReasonCode,
            String eventReasonCode,
            Instant appliedAt,
            Instant effectiveAt,
            Instant expiresAt,
            UUID sourceCaseId,
            UUID actorOperatorId,
            UUID correlationId,
            Instant occurredAt,
            long previousVersion,
            long newVersion) {

        public Mutation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(sanctionId, "sanctionId");
            Objects.requireNonNull(sanctionType, "sanctionType");
            Objects.requireNonNull(afterStatus, "afterStatus");
            Objects.requireNonNull(sanctionReasonCode, "sanctionReasonCode");
            Objects.requireNonNull(eventReasonCode, "eventReasonCode");
            Objects.requireNonNull(appliedAt, "appliedAt");
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (newVersion != previousVersion + 1) {
                throw new IllegalArgumentException("mutation version must advance exactly once");
            }
        }

        public AccountSanctionState.Sanction toSanction() {
            return new AccountSanctionState.Sanction(
                    sanctionId, sanctionType, afterStatus, sanctionReasonCode,
                    appliedAt, effectiveAt, expiresAt, sourceCaseId);
        }

        public enum Kind {
            APPLY,
            LIFT,
            EXPIRE
        }
    }

    public enum Status {
        APPLIED,
        NO_OP,
        REJECTED
    }
}
