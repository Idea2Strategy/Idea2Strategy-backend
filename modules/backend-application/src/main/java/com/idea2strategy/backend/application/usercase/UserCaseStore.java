package com.idea2strategy.backend.application.usercase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserCaseStore {
    CommandResult submit(UserCaseCommand command, Instant now);

    CommandResult supplement(UserCaseSupplementCommand command, Instant now);

    Optional<UserCaseView> findOwned(UUID accountId, UUID caseId);

    record CommandResult(Outcome outcome, UserCaseView view) {
        public enum Outcome {
            APPLIED,
            REPLAYED,
            IDEMPOTENCY_CONFLICT,
            RESOURCE_NOT_AVAILABLE,
            TRANSITION_NOT_ALLOWED,
            STALE_VERSION
        }
    }
}
