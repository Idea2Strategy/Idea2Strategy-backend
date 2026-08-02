package com.idea2strategy.backend.persistence.outbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalOutboxStore {
    public enum ReceiptDisposition { ACQUIRED, IN_PROGRESS, COMPLETED, PERMANENT_FAILURE }

    public record ClaimedMessage(
            UUID messageId,
            String ownerDomain,
            UUID aggregateId,
            String eventType,
            String schemaVersion,
            String payload,
            String payloadHash,
            String producerIdempotencyKey,
            UUID claimToken,
            int attemptNumber,
            Instant claimedAt,
            Instant claimExpiresAt) {}

    public record ReceiptClaim(
            ReceiptDisposition disposition,
            UUID claimToken,
            String resultHash,
            String failureCode) {}

    private final JdbcTemplate jdbc;

    public TransactionalOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<ClaimedMessage> claimDue(
            String workerId, String runtimePolicyVersion, Duration leaseDuration, int limit) {
        requireText(workerId, "workerId");
        requireText(runtimePolicyVersion, "runtimePolicyVersion");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }

        List<UUID> ids = jdbc.queryForList("""
                select id
                from operations.outbox_messages
                where (delivery_status = 'PENDING'
                         and (next_attempt_at is null or next_attempt_at <= clock_timestamp()))
                   or (delivery_status = 'CLAIMED' and claim_expires_at <= clock_timestamp())
                order by coalesce(next_attempt_at, created_at), created_at, id
                for update skip locked
                limit ?
                """, UUID.class, limit);

        List<ClaimedMessage> claimed = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Map<String, Object> head = jdbc.queryForMap("""
                    select delivery_status::text as delivery_status, claim_token,
                           publish_attempt_count
                    from operations.outbox_messages where id = ?
                    """, id);
            Instant now = databaseNow();
            if ("CLAIMED".equals(head.get("delivery_status"))) {
                int expired = jdbc.update("""
                        update operations.outbox_delivery_attempts
                        set completed_at = ?, outcome = 'LEASE_EXPIRED', failure_code = 'CLAIM_LEASE_EXPIRED'
                        where outbox_message_id = ? and claim_token = ? and completed_at is null
                        """, Timestamp.from(now), id, head.get("claim_token"));
                if (expired != 1) {
                    throw new OutboxConflictException("expired claim attempt is missing");
                }
            }

            int attempt = ((Number) head.get("publish_attempt_count")).intValue() + 1;
            UUID token = UUID.randomUUID();
            Instant expiresAt = now.plus(leaseDuration);
            int updated = jdbc.update("""
                    update operations.outbox_messages
                    set delivery_status = 'CLAIMED', claim_token = ?, claimed_by = ?,
                        claimed_at = ?, claim_expires_at = ?, publish_attempt_count = ?,
                        next_attempt_at = null, last_failure_code = null
                    where id = ?
                    """, token, workerId, Timestamp.from(now), Timestamp.from(expiresAt), attempt, id);
            if (updated != 1) {
                throw new OutboxConflictException("message could not be claimed");
            }
            jdbc.update("""
                    insert into operations.outbox_delivery_attempts
                        (outbox_message_id, attempt_number, claim_token, worker_id,
                         runtime_policy_version, claimed_at, claim_expires_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, id, attempt, token, workerId, runtimePolicyVersion,
                    Timestamp.from(now), Timestamp.from(expiresAt));

            claimed.add(jdbc.queryForObject("""
                    select id, owner_domain, aggregate_id, event_type, event_schema_version,
                           payload_document::text, payload_hash, producer_idempotency_key
                    from operations.outbox_messages where id = ?
                    """, (rs, row) -> new ClaimedMessage(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    rs.getString(8), token, attempt, now, expiresAt), id));
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public void acknowledge(UUID messageId, UUID claimToken, String transportMessageKey) {
        requireIds(messageId, claimToken);
        Instant now = databaseNow();
        int updated = jdbc.update("""
                update operations.outbox_messages
                set delivery_status = 'PUBLISHED', published_at = ?, claim_token = null,
                    claimed_by = null, claimed_at = null, claim_expires_at = null,
                    next_attempt_at = null, last_failure_code = null
                where id = ? and delivery_status = 'CLAIMED' and claim_token = ?
                  and claim_expires_at > ?
                """, Timestamp.from(now), messageId, claimToken, Timestamp.from(now));
        if (updated != 1) {
            throw new OutboxConflictException("stale or expired outbox claim");
        }
        completeAttempt(messageId, claimToken, now, "PUBLISHED", transportMessageKey, null, null);
    }

    @Transactional
    public void retry(UUID messageId, UUID claimToken, String failureCode, Instant nextAttemptAt) {
        requireIds(messageId, claimToken);
        requireText(failureCode, "failureCode");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Instant now = databaseNow();
        int updated = jdbc.update("""
                update operations.outbox_messages
                set delivery_status = 'PENDING', claim_token = null, claimed_by = null,
                    claimed_at = null, claim_expires_at = null, next_attempt_at = ?,
                    last_failure_code = ?
                where id = ? and delivery_status = 'CLAIMED' and claim_token = ?
                  and claim_expires_at > ?
                """, Timestamp.from(nextAttemptAt), failureCode, messageId, claimToken, Timestamp.from(now));
        if (updated != 1) {
            throw new OutboxConflictException("stale or expired outbox claim");
        }
        completeAttempt(messageId, claimToken, now, "RETRY_SCHEDULED", null, failureCode, nextAttemptAt);
    }

    @Transactional
    public void deadLetter(UUID messageId, UUID claimToken, String reasonCode) {
        requireIds(messageId, claimToken);
        requireText(reasonCode, "reasonCode");
        Instant now = databaseNow();
        int updated = jdbc.update("""
                update operations.outbox_messages
                set delivery_status = 'DEAD_LETTERED', claim_token = null, claimed_by = null,
                    claimed_at = null, claim_expires_at = null, next_attempt_at = null,
                    last_failure_code = ?, dead_lettered_at = ?, dead_letter_reason_code = ?
                where id = ? and delivery_status = 'CLAIMED' and claim_token = ?
                  and claim_expires_at > ?
                """, reasonCode, Timestamp.from(now), reasonCode,
                messageId, claimToken, Timestamp.from(now));
        if (updated != 1) {
            throw new OutboxConflictException("stale or expired outbox claim");
        }
        completeAttempt(messageId, claimToken, now, "DEAD_LETTERED", null, reasonCode, null);
    }

    @Transactional
    public UUID replay(
            UUID sourceMessageId,
            UUID operatorId,
            String reasonCode,
            UUID correlationId,
            String commandIdempotencyKey) {
        requireIds(sourceMessageId, operatorId);
        Objects.requireNonNull(correlationId, "correlationId");
        requireText(reasonCode, "reasonCode");
        requireText(commandIdempotencyKey, "commandIdempotencyKey");
        String requestHash = sha256(sourceMessageId + "|" + operatorId + "|" + reasonCode + "|" + correlationId);

        List<Map<String, Object>> prior = jdbc.queryForList("""
                select id, target_id, before_hash from operations.audit_events
                where idempotency_key = ?
                """, commandIdempotencyKey);
        if (!prior.isEmpty()) {
            Map<String, Object> row = prior.getFirst();
            if (!requestHash.equals(row.get("before_hash"))) {
                throw new OutboxConflictException("replay idempotency payload mismatch");
            }
            return (UUID) row.get("target_id");
        }

        if (!hasReplayPermission(operatorId)) {
            throw new OutboxAuthorizationException("operator lacks OPERATIONS_OUTBOX_REPLAY");
        }

        Map<String, Object> source = jdbc.queryForMap("""
                select *, delivery_status::text as status_text
                from operations.outbox_messages where id = ? for update
                """, sourceMessageId);
        prior = jdbc.queryForList("""
                select id, target_id, before_hash from operations.audit_events
                where idempotency_key = ?
                """, commandIdempotencyKey);
        if (!prior.isEmpty()) {
            Map<String, Object> row = prior.getFirst();
            if (!requestHash.equals(row.get("before_hash"))) {
                throw new OutboxConflictException("replay idempotency payload mismatch");
            }
            return (UUID) row.get("target_id");
        }
        if (!"DEAD_LETTERED".equals(source.get("status_text"))) {
            throw new OutboxConflictException("only dead-lettered messages may be replayed");
        }
        UUID original = source.get("original_message_id") == null
                ? sourceMessageId : (UUID) source.get("original_message_id");
        if (!original.equals(sourceMessageId)) {
            jdbc.queryForObject("select id from operations.outbox_messages where id = ? for update", UUID.class, original);
        }
        int sequence = jdbc.queryForObject("""
                select coalesce(max(replay_sequence), 0) + 1
                from operations.outbox_messages where original_message_id = ?
                """, Integer.class, original);
        UUID replayId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        Instant now = databaseNow();

        jdbc.update("""
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id,
                     reason_code, correlation_id, idempotency_key, before_hash, after_hash,
                     occurred_at)
                values (?, 'OPERATOR', ?, 'OPERATIONS_OUTBOX_REPLAY', 'OUTBOX', ?,
                        ?, ?, ?, ?, ?, ?)
                """, auditId, operatorId, replayId, reasonCode, correlationId,
                commandIdempotencyKey, requestHash, source.get("payload_hash"), Timestamp.from(now));

        jdbc.update("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                     event_schema_version, payload_document, payload_hash,
                     producer_idempotency_key, idempotency_key, original_message_id,
                     replayed_from_message_id, replay_sequence, replay_audit_event_id,
                     delivery_status, created_at)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, replayId, source.get("owner_domain"), source.get("aggregate_id"),
                source.get("aggregate_sequence"), source.get("event_type"),
                source.get("event_schema_version"), source.get("payload_document").toString(),
                source.get("payload_hash"), source.get("producer_idempotency_key"),
                "outbox-replay:" + commandIdempotencyKey, original, sourceMessageId,
                sequence, auditId, Timestamp.from(now));
        return replayId;
    }

    @Transactional
    public ReceiptClaim receive(
            String handlerId,
            UUID messageId,
            String producerIdempotencyKey,
            String payloadHash,
            String workerId,
            Duration leaseDuration) {
        requireText(handlerId, "handlerId");
        requireText(producerIdempotencyKey, "producerIdempotencyKey");
        requireText(payloadHash, "payloadHash");
        requireText(workerId, "workerId");
        Objects.requireNonNull(messageId, "messageId");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Map<String, Object> envelope = jdbc.queryForMap("""
                select producer_idempotency_key, payload_hash
                from operations.outbox_messages where id = ?
                """, messageId);
        if (!producerIdempotencyKey.equals(envelope.get("producer_idempotency_key"))
                || !payloadHash.equals(envelope.get("payload_hash"))) {
            throw new OutboxConflictException("consumer payload evidence conflicts with immutable envelope");
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select *, status::text as status_text
                from operations.outbox_consumer_receipts
                where consumer_handler_id = ? and outbox_message_id = ? for update
                """, handlerId, messageId);
        Instant now = databaseNow();
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.getFirst();
            if (!payloadHash.equals(row.get("payload_hash"))
                    || !producerIdempotencyKey.equals(row.get("producer_idempotency_key"))) {
                throw new OutboxConflictException("consumer receipt payload hash conflict");
            }
            String status = row.get("status_text").toString();
            jdbc.update("""
                    update operations.outbox_consumer_receipts set last_received_at = ?,
                        receive_attempt_count = receive_attempt_count + 1
                    where consumer_handler_id = ? and outbox_message_id = ?
                    """, Timestamp.from(now), handlerId, messageId);
            if ("COMPLETED".equals(status)) {
                return new ReceiptClaim(ReceiptDisposition.COMPLETED, null,
                        (String) row.get("result_hash"), null);
            }
            if ("PERMANENT_FAILURE".equals(status)) {
                return new ReceiptClaim(ReceiptDisposition.PERMANENT_FAILURE, null,
                        null, (String) row.get("failure_code"));
            }
            Timestamp expires = (Timestamp) row.get("claim_expires_at");
            if ("PROCESSING".equals(status) && expires != null && expires.toInstant().isAfter(now)) {
                return new ReceiptClaim(ReceiptDisposition.IN_PROGRESS, null, null, null);
            }
        }

        UUID token = UUID.randomUUID();
        Instant expiresAt = now.plus(leaseDuration);
        if (rows.isEmpty()) {
            jdbc.update("""
                    insert into operations.outbox_consumer_receipts
                        (consumer_handler_id, outbox_message_id, producer_idempotency_key,
                         payload_hash, status, claim_token, claimed_by, claimed_at,
                         claim_expires_at, first_received_at, last_received_at)
                    values (?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?, ?, ?)
                    """, handlerId, messageId, producerIdempotencyKey, payloadHash,
                    token, workerId, Timestamp.from(now), Timestamp.from(expiresAt),
                    Timestamp.from(now), Timestamp.from(now));
        } else {
            jdbc.update("""
                    update operations.outbox_consumer_receipts
                    set status = 'PROCESSING', claim_token = ?, claimed_by = ?, claimed_at = ?,
                        claim_expires_at = ?, completed_at = null, result_hash = null,
                        failure_code = null
                    where consumer_handler_id = ? and outbox_message_id = ?
                    """, token, workerId, Timestamp.from(now), Timestamp.from(expiresAt),
                    handlerId, messageId);
        }
        return new ReceiptClaim(ReceiptDisposition.ACQUIRED, token, null, null);
    }

    @Transactional
    public void completeReceipt(String handlerId, UUID messageId, UUID claimToken, String resultHash) {
        requireText(resultHash, "resultHash");
        Instant now = databaseNow();
        int updated = jdbc.update("""
                update operations.outbox_consumer_receipts
                set status = 'COMPLETED', claim_token = null, claimed_by = null,
                    claimed_at = null, claim_expires_at = null, completed_at = ?,
                    result_hash = ?, failure_code = null, last_received_at = ?
                where consumer_handler_id = ? and outbox_message_id = ?
                  and status = 'PROCESSING' and claim_token = ? and claim_expires_at > ?
                """, Timestamp.from(now), resultHash, Timestamp.from(now), handlerId,
                messageId, claimToken, Timestamp.from(now));
        if (updated != 1) {
            throw new OutboxConflictException("stale or expired consumer claim");
        }
    }

    @Transactional
    public void failReceipt(
            String handlerId, UUID messageId, UUID claimToken, String failureCode, boolean permanent) {
        requireText(failureCode, "failureCode");
        Instant now = databaseNow();
        String status = permanent ? "PERMANENT_FAILURE" : "RETRYABLE_FAILURE";
        int updated = jdbc.update("""
                update operations.outbox_consumer_receipts
                set status = cast(? as operations.consumer_receipt_status), claim_token = null,
                    claimed_by = null, claimed_at = null, claim_expires_at = null,
                    failure_code = ?, last_received_at = ?
                where consumer_handler_id = ? and outbox_message_id = ?
                  and status = 'PROCESSING' and claim_token = ? and claim_expires_at > ?
                """, status, failureCode, Timestamp.from(now), handlerId, messageId,
                claimToken, Timestamp.from(now));
        if (updated != 1) {
            throw new OutboxConflictException("stale or expired consumer claim");
        }
    }

    private boolean hasReplayPermission(UUID operatorId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from operations.operator_accounts oa
                join operations.operator_role_assignments a on a.operator_account_id = oa.id
                join operations.roles r on r.id = a.role_id
                join operations.role_permissions rp on rp.role_id = r.id
                join operations.permissions p on p.id = rp.permission_id
                where oa.id = ? and oa.status = 'ACTIVE' and oa.disabled_at is null
                  and oa.mfa_enrolled_at is not null and oa.last_mfa_verified_at is not null
                  and r.status = 'ACTIVE' and p.code = 'OPERATIONS_OUTBOX_REPLAY'
                  and a.granted_at <= clock_timestamp()
                  and (a.expires_at is null or a.expires_at > clock_timestamp())
                  and a.revoked_at is null
                """, Integer.class, operatorId);
        return count != null && count > 0;
    }

    private void completeAttempt(
            UUID messageId, UUID claimToken, Instant completedAt, String outcome,
            String transportKey, String failureCode, Instant nextAttemptAt) {
        int updated = jdbc.update("""
                update operations.outbox_delivery_attempts
                set completed_at = ?, outcome = cast(? as operations.outbox_attempt_outcome),
                    transport_message_key = ?, failure_code = ?, next_attempt_at = ?
                where outbox_message_id = ? and claim_token = ? and completed_at is null
                """, Timestamp.from(completedAt), outcome, transportKey, failureCode,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt), messageId, claimToken);
        if (updated != 1) {
            throw new OutboxConflictException("outbox attempt is already completed");
        }
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(
                jdbc.queryForObject("select clock_timestamp()", Timestamp.class)).toInstant();
    }

    private static void requireIds(UUID first, UUID second) {
        Objects.requireNonNull(first, "first id");
        Objects.requireNonNull(second, "second id");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static class OutboxConflictException extends RuntimeException {
        public OutboxConflictException(String message) { super(message); }
    }

    public static class OutboxAuthorizationException extends RuntimeException {
        public OutboxAuthorizationException(String message) { super(message); }
    }
}
