package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.persistence.notification.NotificationEmailWorker;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.ClaimedMessage;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;

public class NotificationDeliveryBatchCategoryPort implements BatchCategoryPort {
    private final TransactionalOutboxStore outbox;
    private final NotificationEmailWorker worker;
    private final int maximumAttempts;
    private final Duration retryDelay;
    private final JdbcTemplate jdbc;
    private final Map<UUID, ClaimedMessage> claims = new ConcurrentHashMap<>();

    public NotificationDeliveryBatchCategoryPort(
            TransactionalOutboxStore outbox, NotificationEmailWorker worker,
            int maximumAttempts, Duration retryDelay, JdbcTemplate jdbc) {
        this.outbox = outbox;
        this.worker = worker;
        this.maximumAttempts = maximumAttempts;
        this.retryDelay = retryDelay;
        this.jdbc = jdbc;
    }

    @Override public BatchCategory category() { return BatchCategory.NOTIFICATION; }

    @Override
    public ClaimPage claimDue(ClaimRequest request) {
        var messages = outbox.claimDueMatching(
                request.workerId(), request.runtimePolicyVersion(), request.leaseDuration(), request.limit(),
                "notification", "NOTIFICATION_EMAIL_DELIVERY");
        messages.forEach(message -> claims.put(message.messageId(), message));
        var items = messages.stream().map(message -> new WorkItem(
                category(), message.messageId().toString(), message.claimedAt(),
                message.producerIdempotencyKey(), message.claimToken(), message.attemptNumber())).toList();
        var next = items.isEmpty() ? null : new Cursor(items.getLast().dueAt(), items.getLast().itemId());
        return new ClaimPage(
                messages.isEmpty()
                        ? jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant()
                        : messages.getFirst().claimedAt(), items, next);
    }

    @Override
    public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
        ClaimedMessage message = claims.remove(UUID.fromString(item.itemId()));
        if (message == null || !message.claimToken().equals(item.claimToken())) {
            return ItemResult.retryable("NOTIFICATION_CLAIM_NOT_OWNED");
        }
        return switch (worker.deliver(message, "notification-delivery-v1", maximumAttempts, retryDelay)) {
            case COMPLETED -> ItemResult.completed();
            case RETRY -> ItemResult.retryable("NOTIFICATION_DELIVERY_RETRY_SCHEDULED");
            case DEAD_LETTER -> ItemResult.permanent("NOTIFICATION_DELIVERY_DEAD_LETTERED");
        };
    }
}
