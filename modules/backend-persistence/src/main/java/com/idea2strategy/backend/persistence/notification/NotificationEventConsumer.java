package com.idea2strategy.backend.persistence.notification;

import com.idea2strategy.backend.application.notification.NotificationRequest;
import com.idea2strategy.backend.application.notification.NotificationService;
import com.idea2strategy.backend.application.notification.NotificationStore.NotificationReceipt;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.ClaimedMessage;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore.ReceiptDisposition;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationEventConsumer {
    static final String HANDLER_ID = "A18_NOTIFICATION_CREATE_V1";

    private final TransactionalOutboxStore outbox;
    private final NotificationPersistenceAdapter notifications;
    private final Clock clock;

    public NotificationEventConsumer(
            TransactionalOutboxStore outbox, NotificationPersistenceAdapter notifications, Clock clock) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public NotificationReceipt consume(
            ClaimedMessage source, NotificationRequest request, String workerId, Duration leaseDuration) {
        if (!source.messageId().toString().equals(request.sourceEventId())
                || !source.payloadHash().equals(request.sourceEventHash())) {
            throw new IllegalArgumentException("notification request does not match its source envelope");
        }
        var claim = outbox.receive(HANDLER_ID, source.messageId(), source.producerIdempotencyKey(),
                source.payloadHash(), workerId, leaseDuration);
        if (claim.disposition() == ReceiptDisposition.IN_PROGRESS) {
            throw new IllegalStateException("notification source is already being processed");
        }
        if (claim.disposition() == ReceiptDisposition.PERMANENT_FAILURE) {
            throw new IllegalStateException("notification source was permanently rejected");
        }
        NotificationService service = new NotificationService(notifications, notifications, notifications, clock);
        NotificationReceipt receipt = service.create(request);
        if (claim.disposition() == ReceiptDisposition.ACQUIRED) {
            outbox.completeReceipt(HANDLER_ID, source.messageId(), claim.claimToken(),
                    receipt.notificationId().toString());
        }
        return receipt;
    }
}
