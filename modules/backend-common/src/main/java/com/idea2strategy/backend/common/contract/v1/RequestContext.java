package com.idea2strategy.backend.common.contract.v1;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RequestContext(UUID correlationId, String idempotencyKey) {
    public RequestContext {
        Objects.requireNonNull(correlationId, "correlationId is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }

    public EventEnvelope event(
            String eventType,
            AuthenticationPrincipal actor,
            Instant occurredAt,
            Map<String, Object> payload) {
        var eventId = UUID.nameUUIDFromBytes((idempotencyKey + "|" + eventType).getBytes(StandardCharsets.UTF_8));
        return new EventEnvelope(
                CommonContractVersions.EVENT_ENVELOPE_V1,
                eventId,
                eventType,
                actor,
                occurredAt,
                correlationId,
                idempotencyKey,
                payload);
    }
}
