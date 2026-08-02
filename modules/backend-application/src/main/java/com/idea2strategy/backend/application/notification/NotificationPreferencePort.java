package com.idea2strategy.backend.application.notification;

import java.util.Set;
import java.util.UUID;

public interface NotificationPreferencePort {
    Set<NotificationChannel> enabledChannels(UUID accountId, String typeCode, String policyVersion);
}
