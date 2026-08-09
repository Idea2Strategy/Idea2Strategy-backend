package com.idea2strategy.backend.application.notification;

import java.time.Instant;
import java.util.UUID;

public interface EmailNotificationPreferencePort {
    boolean enabled(UUID accountId);

    void replace(UUID accountId, boolean enabled, Instant updatedAt);
}
