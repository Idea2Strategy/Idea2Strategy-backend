package com.idea2strategy.backend.persistence.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.ClaimedMessage;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationEmailWorker {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionalOutboxStore outbox;
    private final EmailDeliveryGateway gateway;

    public NotificationEmailWorker(
            JdbcTemplate jdbc, ObjectMapper json, TransactionalOutboxStore outbox,
            EmailDeliveryGateway gateway) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.gateway = gateway;
    }

    public void deliver(
            ClaimedMessage message, String runtimePolicyVersion,
            int maximumAttempts, Duration retryDelay) {
        if (!"notification".equals(message.ownerDomain())
                || !"NOTIFICATION_EMAIL_DELIVERY".equals(message.eventType())) {
            throw new IllegalArgumentException("claim is not a notification email delivery");
        }
        UUID notificationId = notificationId(message.payload());
        var snapshot = jdbc.queryForObject("""
                select template_version, locale, payload_document::text
                from operations.notifications where id = ?
                """, (rs, row) -> new EmailDeliveryGateway.EmailMessage(notificationId,
                rs.getString(1), rs.getString(2), readArguments(rs.getString(3))), notificationId);
        Instant attemptedAt = databaseNow();
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                insert into operations.delivery_attempts
                    (id, notification_id, channel, attempt_number, status, attempted_at,
                     outbox_message_id, runtime_policy_version)
                values (?, ?, 'EMAIL', ?, 'RUNNING', ?, ?, ?)
                """, attemptId, notificationId, message.attemptNumber(), Timestamp.from(attemptedAt),
                message.messageId(), runtimePolicyVersion);

        EmailDeliveryGateway.DeliveryResult result = gateway.send(snapshot);
        Instant completedAt = databaseNow();
        if (result.outcome() == EmailDeliveryGateway.DeliveryResult.Outcome.SENT) {
            finish(attemptId, "SUCCEEDED", completedAt, result.providerMessageKey(), null, null);
            outbox.acknowledge(message.messageId(), message.claimToken(), result.providerMessageKey());
        } else if (result.outcome() == EmailDeliveryGateway.DeliveryResult.Outcome.RETRYABLE_FAILURE
                && message.attemptNumber() < maximumAttempts) {
            Instant next = completedAt.plus(retryDelay);
            finish(attemptId, "FAILED", completedAt, null, result.failureCode(), next);
            outbox.retry(message.messageId(), message.claimToken(), result.failureCode(), next);
        } else {
            finish(attemptId, "FAILED", completedAt, null, result.failureCode(), null);
            outbox.deadLetter(message.messageId(), message.claimToken(), result.failureCode());
        }
    }

    private void finish(UUID id, String status, Instant completedAt, String providerKey,
                        String failureCode, Instant nextAttemptAt) {
        jdbc.update("""
                update operations.delivery_attempts
                set status = cast(? as operations.work_status), completed_at = ?,
                    provider_message_key = ?, failure_code = ?, next_attempt_at = ?
                where id = ? and status = 'RUNNING'
                """, status, Timestamp.from(completedAt), providerKey, failureCode,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt), id);
    }

    private UUID notificationId(String payload) {
        try { return UUID.fromString(json.readTree(payload).path("notificationId").asText()); }
        catch (Exception e) { throw new IllegalArgumentException("invalid email delivery payload", e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readArguments(String value) {
        try { return json.readValue(value, Map.class); }
        catch (Exception e) { throw new IllegalStateException("invalid notification snapshot", e); }
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }
}
