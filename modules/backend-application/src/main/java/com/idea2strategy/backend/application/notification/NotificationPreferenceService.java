package com.idea2strategy.backend.application.notification;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class NotificationPreferenceService {
    private final NotificationPolicyPort policies;
    private final NotificationPreferenceManagementPort preferences;
    private final Clock clock;

    public NotificationPreferenceService(
            NotificationPolicyPort policies,
            NotificationPreferenceManagementPort preferences,
            Clock clock) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<NotificationPreferenceView> list(UUID accountId) {
        return preferences.list(Objects.requireNonNull(accountId, "accountId"));
    }

    public NotificationPreferenceView replace(
            UUID accountId, String typeCode, Set<NotificationChannel> enabledChannels) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(enabledChannels, "enabledChannels");
        NotificationPolicy policy = policies.requireActive(typeCode);
        Set<NotificationChannel> selected = Set.copyOf(enabledChannels);
        if (!selected.contains(NotificationChannel.APP)) {
            throw new IllegalArgumentException("APP notifications cannot be disabled");
        }
        if (!policy.defaultChannels().containsAll(selected)) {
            throw new IllegalArgumentException("channel is not available for this notification type");
        }
        if (policy.mandatory() && !selected.equals(policy.defaultChannels())) {
            throw new IllegalArgumentException("mandatory notification channels cannot be disabled");
        }
        preferences.replace(accountId, policy.typeCode(), policy.policyVersion(), selected, clock.instant());
        return new NotificationPreferenceView(
                policy.typeCode(), policy.policyVersion(), policy.mandatory(), selected);
    }
}
