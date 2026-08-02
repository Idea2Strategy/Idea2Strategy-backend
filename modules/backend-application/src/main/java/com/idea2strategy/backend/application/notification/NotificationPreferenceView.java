package com.idea2strategy.backend.application.notification;

import java.util.Set;

public record NotificationPreferenceView(
        String typeCode,
        String policyVersion,
        boolean mandatory,
        Set<NotificationChannel> enabledChannels) {
    public NotificationPreferenceView {
        enabledChannels = Set.copyOf(enabledChannels);
    }
}
