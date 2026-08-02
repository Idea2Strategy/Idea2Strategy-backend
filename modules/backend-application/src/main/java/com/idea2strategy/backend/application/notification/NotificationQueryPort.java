package com.idea2strategy.backend.application.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface NotificationQueryPort {
    NotificationPage listOwned(UUID accountId, Instant beforeCreatedAt, UUID beforeId, int limit);

    record NotificationItem(
            UUID id,
            String typeCode,
            boolean mandatory,
            String templateVersion,
            String locale,
            Map<String, String> templateArguments,
            Instant createdAt,
            Instant readAt) {
        public NotificationItem {
            templateArguments = Map.copyOf(templateArguments);
        }
    }

    record NotificationPage(List<NotificationItem> items, Instant nextCreatedAt, UUID nextId) {
        public NotificationPage {
            items = List.copyOf(items);
        }
    }
}
