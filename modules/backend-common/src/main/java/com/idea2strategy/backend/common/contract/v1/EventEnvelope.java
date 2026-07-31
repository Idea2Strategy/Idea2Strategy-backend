package com.idea2strategy.backend.common.contract.v1;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(
        String schemaVersion,
        UUID eventId,
        String eventType,
        AuthenticationPrincipal actor,
        Instant occurredAt,
        UUID correlationId,
        String idempotencyKey,
        Map<String, Object> payload) {

    public EventEnvelope {
        schemaVersion = CommonContractVersions.require(CommonContractVersions.EVENT_ENVELOPE_V1, schemaVersion);
        Objects.requireNonNull(eventId, "eventId is required");
        eventType = requireText(eventType, "eventType");
        Objects.requireNonNull(actor, "actor is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload is required"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
