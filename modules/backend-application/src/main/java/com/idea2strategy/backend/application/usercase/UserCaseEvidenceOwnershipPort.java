package com.idea2strategy.backend.application.usercase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface UserCaseEvidenceOwnershipPort {
    Optional<VerifiedUserCaseEvidence> verifyOwnedAvailable(
            UUID accountId, UserCaseEvidenceReference evidence, Instant at);
}
