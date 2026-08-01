package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public interface AccountRecoveryCommandPort {
    void issuePasswordReset(PendingPasswordReset reset);

    PasswordResetOutcome consumePasswordReset(PasswordResetConsumption consumption);

    void replaceRecoveryCodes(RecoveryCodeBatch batch);

    RecoveryCodeOutcome consumeRecoveryCode(RecoveryCodeConsumption consumption);

    void recordOidcRecoveryProof(UUID accountId, UUID loginIdentityId, UUID correlationId, Instant verifiedAt);
}
