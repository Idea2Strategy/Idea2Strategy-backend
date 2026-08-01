package com.idea2strategy.backend.application.botcontrol;

import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record BotStopDispatch(
        UUID botId,
        UUID messageId,
        String idempotencyKey,
        BotLifecycleStatus lifecycleStatus,
        String reasonCode,
        boolean created) {
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");

    public BotStopDispatch {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!SHA_256.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("idempotencyKey must use sha256:<64 lowercase hex>");
        }
    }
}
