package com.idea2strategy.backend.persistence.backtest;

import com.idea2strategy.backend.application.backtest.BacktestRequestEnvelope;
import com.idea2strategy.backend.application.backtest.BacktestRequestIdempotencyConflictException;
import com.idea2strategy.backend.application.backtest.BacktestRequestReceipt;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BacktestRequestOutboxStore {
    private final JdbcClient jdbc;

    public BacktestRequestOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Persists a request in the outbox, or returns its existing receipt for an identical retry. */
    @Transactional
    public BacktestRequestReceipt enqueue(BacktestRequestEnvelope request, Instant createdAt) {
        /* Lock the key before looking up the row: a first request has no row to lock yet. */
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", request.producerIdempotencyKey())
                .query((rs, row) -> 1)
                .single();
        var existing = jdbc.sql("""
                select id, event_type, payload_document ->> 'requestHash' as request_hash
                from operations.outbox_messages where idempotency_key = :key for update
                """)
                .param("key", request.producerIdempotencyKey())
                .query((rs, row) -> new Existing(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("request_hash")))
                .optional();
        if (existing.isPresent()) {
            Existing found = existing.orElseThrow();
            /* Reusing a key is valid only for the same event type and request content. */
            if (!request.eventType().equals(found.eventType())
                    || !request.requestHash().equals(found.requestHash())) {
                throw new BacktestRequestIdempotencyConflictException();
            }
            return new BacktestRequestReceipt(found.messageId(), found.eventType(), false, request.aggregateId());
        }

        /* Different idempotency keys can target the same aggregate. Serialize sequence allocation
         * so concurrent requests cannot both insert the same max(sequence) + 1. */
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:aggregateKey, 0))")
                .param("aggregateKey", "backtest-request:" + request.aggregateId())
                .query((rs, row) -> 1)
                .single();
        long sequence = jdbc.sql("""
                select coalesce(max(aggregate_sequence), 0) + 1
                from operations.outbox_messages
                where owner_domain = 'backtest-request' and aggregate_id = :aggregateId
                """)
                .param("aggregateId", request.aggregateId())
                .query(Long.class)
                .single();
        /* Persist the message in this transaction; delivery is handled by the outbox publisher. */
        jdbc.sql("""
                insert into operations.outbox_messages (
                    id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                    event_schema_version, payload_document, producer_idempotency_key,
                    idempotency_key, created_at)
                values (:id, 'backtest-request', :aggregateId, :sequence, :eventType,
                    :schemaVersion, cast(:payload as jsonb), :producerKey, :producerKey, :createdAt)
                """)
                .param("id", request.messageId())
                .param("aggregateId", request.aggregateId())
                .param("sequence", sequence)
                .param("eventType", request.eventType())
                .param("schemaVersion", request.eventSchemaVersion())
                .param("payload", request.payloadDocument())
                .param("producerKey", request.producerIdempotencyKey())
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
        return new BacktestRequestReceipt(request.messageId(), request.eventType(), true, request.aggregateId());
    }

    private record Existing(UUID messageId, String eventType, String requestHash) {}
}
