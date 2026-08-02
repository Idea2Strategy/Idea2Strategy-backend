package com.idea2strategy.backend.application.notification;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class NotificationService {
    private final NotificationPolicyPort policies;
    private final NotificationPreferencePort preferences;
    private final NotificationStore store;
    private final Clock clock;

    public NotificationService(
            NotificationPolicyPort policies,
            NotificationPreferencePort preferences,
            NotificationStore store,
            Clock clock) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public NotificationStore.NotificationReceipt create(NotificationRequest request) {
        NotificationPolicy policy = policies.requireActive(request.typeCode());
        Set<NotificationChannel> selected;
        if (policy.mandatory()) {
            selected = policy.defaultChannels();
        } else {
            var enabled = preferences.enabledChannels(
                    request.accountId(), request.typeCode(), policy.policyVersion());
            var intersection = EnumSet.noneOf(NotificationChannel.class);
            intersection.addAll(policy.defaultChannels());
            intersection.retainAll(enabled);
            intersection.add(NotificationChannel.APP);
            selected = Set.copyOf(intersection);
        }
        return store.create(request, policy, selected, clock.instant());
    }

    public void markRead(UUID accountId, UUID notificationId) {
        if (!store.markRead(accountId, notificationId, clock.instant())) {
            throw new NotificationUnavailableException();
        }
    }
}
