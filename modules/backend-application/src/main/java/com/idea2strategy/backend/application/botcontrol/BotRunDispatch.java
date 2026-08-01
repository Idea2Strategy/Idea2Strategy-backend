package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record BotRunDispatch(
        UUID botId,
        UUID messageId,
        String idempotencyKey,
        Instant executionEligibleFrom,
        BotRunDispatchMode mode,
        boolean created) {
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");

    public BotRunDispatch {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(executionEligibleFrom, "executionEligibleFrom");
        Objects.requireNonNull(mode, "mode");
        if (!SHA_256.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("idempotencyKey must use sha256:<64 lowercase hex>");
        }
    }
}
