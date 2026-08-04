package com.idea2strategy.backend.persistence.competition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.ClaimedMessage;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.ReceiptDisposition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies F's durable room-ledger outcome without ever writing an F-owned table. */
@Component
public class RoomEvaluationAccountResultConsumer {
    static final String HANDLER_ID = "backend.room-evaluation-account-result.v1";
    private static final String OPENED = "ROOM_EVALUATION_ACCOUNT_OPENED";
    private static final String REJECTED = "ROOM_EVALUATION_ACCOUNT_OPEN_REJECTED";
    private static final UUID SYSTEM_ACTOR = UUID.nameUUIDFromBytes("backend-room-ledger-consumer".getBytes(StandardCharsets.UTF_8));

    private final TransactionalOutboxStore outbox;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public RoomEvaluationAccountResultConsumer(TransactionalOutboxStore outbox, JdbcTemplate jdbc) {
        this(outbox, jdbc, Clock.systemUTC());
    }

    RoomEvaluationAccountResultConsumer(
            TransactionalOutboxStore outbox, JdbcTemplate jdbc, Clock clock) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = new ObjectMapper();
        this.clock = clock;
    }

    @Transactional
    public Outcome consume(ClaimedMessage source, String workerId, Duration leaseDuration) {
        if (!"trading".equals(source.ownerDomain())
                || !(OPENED.equals(source.eventType()) || REJECTED.equals(source.eventType()))) {
            throw new IllegalArgumentException("unsupported room account result envelope");
        }
        var expectedSchema = OPENED.equals(source.eventType())
                ? "room-evaluation-account-opened.v1" : "room-evaluation-account-open-rejected.v1";
        if (!expectedSchema.equals(source.schemaVersion())) {
            throw new IllegalArgumentException("unsupported room account result schema");
        }
        var claim = outbox.receive(HANDLER_ID, source.messageId(), source.producerIdempotencyKey(),
                source.payloadHash(), workerId, leaseDuration);
        boolean receiptAlreadyCompleted = claim.disposition() == ReceiptDisposition.COMPLETED;
        if (receiptAlreadyCompleted) {
            Integer unapplied = jdbc.queryForObject("select count(*) from competition.room_evaluation_account_results "
                    + "where result_message_id = ? and applied_at is null", Integer.class, source.messageId());
            if (unapplied == null || unapplied == 0) return Outcome.DUPLICATE;
        }
        if (claim.disposition() == ReceiptDisposition.IN_PROGRESS) return Outcome.IN_PROGRESS;
        if (claim.disposition() == ReceiptDisposition.PERMANENT_FAILURE) return Outcome.CONFLICT;

        try {
            JsonNode result = json.readTree(source.payload());
            UUID requestMessageId = uuid(result, "requestMessageId");
            UUID participationId = uuid(result, "participationId");
            UUID botId = uuid(result, "botId");
            UUID segmentId = uuid(result, "evaluationSegmentId");
            String requestHash = text(result, "requestPayloadHash");
            String producerKey = text(result, "producerIdempotencyKey");
            String reason = REJECTED.equals(source.eventType()) ? text(result, "reasonCode") : null;

            jdbc.update("""
                    insert into competition.room_evaluation_account_results
                        (request_message_id, result_message_id, participation_id, bot_id,
                         evaluation_segment_id, result_type, producer_idempotency_key,
                         request_payload_hash, result_payload_hash, payload_document,
                         received_at, failure_code)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                    on conflict (request_message_id) do nothing
                    """, requestMessageId, source.messageId(), participationId, botId, segmentId,
                    OPENED.equals(source.eventType()) ? "OPENED" : "REJECTED", producerKey,
                    requestHash, source.payloadHash(), source.payload(), now(), reason);

            var requestRows = jdbc.queryForList("""
                    select payload_document::text as payload, payload_hash, producer_idempotency_key
                    from operations.outbox_messages
                    where id = ? and owner_domain = 'room-performance'
                      and event_type = 'ROOM_EVALUATION_ACCOUNT_OPEN_REQUESTED'
                    """, requestMessageId);
            if (requestRows.isEmpty()) {
                if (!receiptAlreadyCompleted) outbox.completeReceipt(
                        HANDLER_ID, source.messageId(), claim.claimToken(), source.payloadHash());
                return Outcome.RETAINED_OUT_OF_ORDER;
            }
            Map<String, Object> requestEnvelope = requestRows.getFirst();
            JsonNode request = json.readTree(requestEnvelope.get("payload").toString());
            requireMatch(requestEnvelope.get("payload_hash"), requestHash, "request hash");
            requireMatch(requestEnvelope.get("producer_idempotency_key"), producerKey, "producer key");
            requireMatch(uuid(request, "participationId"), participationId, "participation");
            requireMatch(uuid(request, "botId"), botId, "bot");
            requireMatch(uuid(request, "evaluationSegmentId"), segmentId, "segment");
            requireMatch(text(request, "initialCash"), text(result, "initialCash"), "initial cash");
            requireMatch(text(request, "currency"), text(result, "currency"), "currency");
            requireMatch(uuid(request, "feePolicyVersionId"), uuid(result, "feePolicyVersionId"), "fee policy");
            requireMatch(uuid(request, "buyingPowerPolicyVersionId"),
                    uuid(result, "buyingPowerPolicyVersionId"), "buying power policy");

            Integer pending = jdbc.queryForObject(
                    "select count(*) from competition.participations where id = ? and bot_id = ? "
                            + "and status = 'PENDING_LEDGER'", Integer.class, participationId, botId);
            if (pending == null || pending != 1) throw new Conflict("participation is not awaiting this ledger");

            if (OPENED.equals(source.eventType())) {
                long sequence = result.path("botEventSequence").asLong(0);
                if (sequence <= 0) throw new Conflict("invalid bot event sequence");
                int segments = jdbc.update("""
                        update competition.live_evaluation_segments
                        set start_event_sequence = ?, initial_state_hash = ?
                        where id = ? and participation_id = ?
                          and start_event_sequence is null and initial_state_hash is null
                        """, sequence, evidenceHash(requestHash, source.payloadHash(), sequence),
                        segmentId, participationId);
                // Backtest rooms intentionally have no live segment row.
                Integer live = jdbc.queryForObject("""
                        select count(*) from competition.rooms r
                        join competition.participations p on p.room_id = r.id
                        where p.id = ? and r.competition_type = 'LIVE_PAPER'
                        """, Integer.class, participationId);
                if (live != null && live == 1 && segments != 1) throw new Conflict("live segment evidence conflict");
                jdbc.update("""
                        update competition.participations
                        set status = 'EVALUATING', evaluation_started_at = ?
                        where id = ? and status = 'PENDING_LEDGER'
                        """, now(), participationId);
                appendParticipationEvent(participationId, "EVALUATION_STARTED", source.messageId(), null);
            } else {
                jdbc.update("""
                        update competition.participations
                        set status = 'EVALUATION_FAILED', evaluation_finished_at = ?, evaluation_failure_code = ?
                        where id = ? and status = 'PENDING_LEDGER'
                        """, now(), reason, participationId);
                appendParticipationEvent(participationId, "EVALUATION_FAILED", source.messageId(), reason);
            }
            jdbc.update("update competition.room_evaluation_account_results set applied_at = ? "
                    + "where request_message_id = ?", now(), requestMessageId);
            if (!receiptAlreadyCompleted) outbox.completeReceipt(
                    HANDLER_ID, source.messageId(), claim.claimToken(), source.payloadHash());
            return OPENED.equals(source.eventType()) ? Outcome.OPENED : Outcome.REJECTED;
        } catch (Conflict conflict) {
            auditConflict(source, conflict.getMessage());
            if (!receiptAlreadyCompleted) outbox.failReceipt(
                    HANDLER_ID, source.messageId(), claim.claimToken(), "ROOM_LEDGER_RESULT_CONFLICT", true);
            return Outcome.CONFLICT;
        } catch (Exception malformed) {
            auditConflict(source, "INVALID_ROOM_LEDGER_RESULT");
            if (!receiptAlreadyCompleted) outbox.failReceipt(
                    HANDLER_ID, source.messageId(), claim.claimToken(), "INVALID_ROOM_LEDGER_RESULT", true);
            return Outcome.CONFLICT;
        }
    }

    /** Replays facts that were durably retained before their local request became visible. */
    @Transactional
    public int reconcilePending(String workerId, Duration leaseDuration, int limit) {
        var rows = jdbc.queryForList("""
                select o.id, o.owner_domain, o.aggregate_id, o.event_type, o.event_schema_version,
                       o.payload_document::text as payload, o.payload_hash, o.producer_idempotency_key
                from competition.room_evaluation_account_results r
                join operations.outbox_messages o on o.id = r.result_message_id
                where r.applied_at is null order by r.received_at, r.result_message_id limit ?
                """, limit);
        int applied = 0;
        for (var row : rows) {
            var source = new ClaimedMessage((UUID) row.get("id"), row.get("owner_domain").toString(),
                    (UUID) row.get("aggregate_id"), row.get("event_type").toString(),
                    row.get("event_schema_version").toString(), row.get("payload").toString(),
                    row.get("payload_hash").toString(), row.get("producer_idempotency_key").toString(),
                    null, 0, clock.instant(), clock.instant().plus(leaseDuration));
            Outcome outcome = consume(source, workerId, leaseDuration);
            if (outcome == Outcome.OPENED || outcome == Outcome.REJECTED) applied++;
        }
        return applied;
    }

    private void appendParticipationEvent(UUID participationId, String type, UUID resultId, String reason) {
        Integer sequence = jdbc.queryForObject("select coalesce(max(event_sequence), 0) + 1 "
                + "from competition.participation_events where participation_id = ?", Integer.class, participationId);
        jdbc.update("""
                insert into competition.participation_events
                    (id, participation_id, event_sequence, event_type, occurred_at, payload_document)
                values (?, ?, ?, ?, ?, jsonb_build_object('resultMessageId', ?::text, 'reasonCode', ?::text))
                """, UUID.nameUUIDFromBytes((type + ":" + resultId).getBytes(StandardCharsets.UTF_8)),
                participationId, sequence, type, now(), resultId, reason);
    }

    private void auditConflict(ClaimedMessage source, String reason) {
        String bounded = reason.substring(0, Math.min(reason.length(), 80));
        jdbc.update("""
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id, reason_code,
                     correlation_id, idempotency_key, before_hash, occurred_at)
                values (?, 'SYSTEM', ?, 'ROOM_EVALUATION_ACCOUNT_RESULT_CONFLICT', 'competition', ?, ?,
                        ?, ?, ?, ?) on conflict (idempotency_key) do nothing
                """, UUID.nameUUIDFromBytes(("room-ledger-conflict:" + source.messageId()).getBytes(StandardCharsets.UTF_8)),
                SYSTEM_ACTOR, source.aggregateId(), bounded, source.messageId(),
                "ROOM_LEDGER_RESULT_CONFLICT:" + source.messageId(), source.payloadHash(), now());
    }

    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }

    private static UUID uuid(JsonNode node, String field) { return UUID.fromString(text(node, field)); }
    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new Conflict("missing " + field);
        return value;
    }
    private static void requireMatch(Object expected, Object actual, String field) {
        if (!expected.equals(actual)) throw new Conflict(field + " mismatch");
    }
    private static String evidenceHash(String requestHash, String resultHash, long sequence) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((requestHash + "\n" + resultHash + "\n" + sequence).getBytes(StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    public enum Outcome { OPENED, REJECTED, DUPLICATE, IN_PROGRESS, RETAINED_OUT_OF_ORDER, CONFLICT }
    private static final class Conflict extends RuntimeException { Conflict(String message) { super(message); } }
}
