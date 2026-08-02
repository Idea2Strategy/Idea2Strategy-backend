package com.idea2strategy.backend.application.caseoperations;

import java.util.Objects;
import java.util.UUID;

public interface CaseSanctionCommandPort {
    Result execute(Request request);

    record Request(
            Operation operation,
            UUID sanctionId,
            UUID caseId,
            long expectedCaseVersion,
            UUID actorOperatorId,
            String reasonCode,
            UUID correlationId,
            String idempotencyKey) {}

    enum Operation {
        APPLY,
        RELEASE
    }

    record Result(Status status, String code, String resultReference) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(code, "code");
        }

        public enum Status {
            APPLIED,
            REJECTED,
            UNKNOWN
        }
    }
}
