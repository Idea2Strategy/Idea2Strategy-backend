package com.idea2strategy.backend.application.notification;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface NotificationStore {
    NotificationReceipt create(
            NotificationRequest request,
            NotificationPolicy policy,
            Set<NotificationChannel> channels,
            Instant now);

    boolean markRead(UUID accountId, UUID notificationId, Instant now);

    record NotificationReceipt(UUID notificationId, Set<NotificationChannel> channels, boolean replayed) {
        public NotificationReceipt {
            channels = Set.copyOf(channels);
        }
    }
}
