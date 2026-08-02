package com.idea2strategy.backend.application.notification;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface NotificationPreferenceManagementPort {
    List<NotificationPreferenceView> list(UUID accountId);

    void replace(
            UUID accountId,
            String typeCode,
            String policyVersion,
            Set<NotificationChannel> enabledChannels,
            Instant updatedAt);
}
