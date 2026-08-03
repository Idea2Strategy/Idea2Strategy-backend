package com.idea2strategy.backend.application.caseoperations;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface CaseResponseDeadlinePort {
    List<Identity> findDue(int limit);

    Result expire(Identity identity, UUID correlationId);

    record Identity(UUID caseId, long expectedCaseVersion, Instant responseDeadlineAt) {
        public Identity {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(responseDeadlineAt, "responseDeadlineAt");
            if (expectedCaseVersion < 1) throw new IllegalArgumentException("expectedCaseVersion");
        }
    }

    record Result(Status status, Identity identity, UUID caseEventId, Instant decidedAt) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(decidedAt, "decidedAt");
            if ((status == Status.APPLIED) != (caseEventId != null)) {
                throw new IllegalArgumentException("CASE_DEADLINE_RESULT_INVALID");
            }
        }

        public enum Status { APPLIED, ALREADY_TRANSITIONED }
    }
}
