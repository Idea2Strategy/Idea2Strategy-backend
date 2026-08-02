package com.idea2strategy.backend.application.notification;

import java.util.Set;

public record NotificationPolicy(
        String typeCode,
        String policyVersion,
        boolean mandatory,
        Set<NotificationChannel> defaultChannels) {
    public NotificationPolicy {
        if (typeCode == null || typeCode.isBlank()) throw new IllegalArgumentException("typeCode is required");
        if (policyVersion == null || policyVersion.isBlank()) throw new IllegalArgumentException("policyVersion is required");
        defaultChannels = Set.copyOf(defaultChannels);
        if (!defaultChannels.contains(NotificationChannel.APP)) {
            throw new IllegalArgumentException("every notification must retain an in-app record");
        }
    }
}
