package com.idea2strategy.backend.application.outbox;

import java.util.Objects;
import java.util.UUID;

/**
 * One outbox row as the transport needs to see it.
 *
 * <p>{@code payloadDocument} is carried exactly as the outbox column rendered it and must not be
 * reserialised, even into equivalent JSON. The column is {@code jsonb}, so the producer's original
 * whitespace and key order are already normalised away before anything reads it; what a consumer can
 * still verify is that the body it received hashes to the {@code payload_hash} the outbox stored,
 * which {@code prepare_outbox_envelope} computes over that same rendering. Reformatting here would
 * break that check silently.
 *
 * <p>The rest of the fields are the routing and triage envelope: which contract this is, which
 * aggregate it belongs to, and the idempotency key a consumer deduplicates on.
 */
public record PublishableOutboxMessage(
        UUID messageId,
        String ownerDomain,
        UUID aggregateId,
        long aggregateSequence,
        String eventType,
        String eventSchemaVersion,
        String idempotencyKey,
        String payloadDocument) {

    public PublishableOutboxMessage {
        Objects.requireNonNull(messageId, "messageId");
        ownerDomain = requireText(ownerDomain, "ownerDomain");
        Objects.requireNonNull(aggregateId, "aggregateId");
        eventType = requireText(eventType, "eventType");
        eventSchemaVersion = requireText(eventSchemaVersion, "eventSchemaVersion");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        payloadDocument = requireText(payloadDocument, "payloadDocument");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
