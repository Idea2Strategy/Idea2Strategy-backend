package com.idea2strategy.backend.application.caseoperations;

import com.idea2strategy.backend.application.accountsanction.AccountSanctionState;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface CaseSanctionCommandPort {
    Result execute(Request request);

    record Request(
            Operation operation,
            UUID sanctionId,
            UUID accountId,
            UUID caseId,
            long expectedCaseVersion,
            long expectedSanctionVersion,
            OperatorRequestContext requestContext,
            AccountSanctionState.Type sanctionType,
            Instant expiresAt,
            String reasonCode,
            UUID correlationId,
            String idempotencyKey,
            String requestHash) {}

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
