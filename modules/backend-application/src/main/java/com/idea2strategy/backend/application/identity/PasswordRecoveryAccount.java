package com.idea2strategy.backend.application.identity;

import java.util.UUID;

public record PasswordRecoveryAccount(
        UUID accountId, UUID loginIdentityId, long authEpoch, long credentialVersion) {}
