package com.idea2strategy.backend.application.identity;

import java.util.UUID;

public record StoredRecoveryCode(UUID id, String digest) {}
