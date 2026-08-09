package com.idea2strategy.backend.application.notification;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class NotificationService {
    private final NotificationPolicyPort policies;
    private final EmailNotificationPreferencePort emailPreferences;
    private final NotificationStore store;
    private final Clock clock;

    public NotificationService(
            NotificationPolicyPort policies,
            EmailNotificationPreferencePort emailPreferences,
            NotificationStore store,
            Clock clock) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.emailPreferences = Objects.requireNonNull(emailPreferences, "emailPreferences");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public NotificationStore.NotificationReceipt create(NotificationRequest request) {
        NotificationPolicy policy = policies.requireActive(request.typeCode());
        Set<NotificationChannel> selected;
        if (policy.mandatory()) {
            selected = policy.defaultChannels();
        } else {
            var intersection = EnumSet.noneOf(NotificationChannel.class);
            intersection.addAll(policy.defaultChannels());
            if (!emailPreferences.enabled(request.accountId())) {
                intersection.remove(NotificationChannel.EMAIL);
            }
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
