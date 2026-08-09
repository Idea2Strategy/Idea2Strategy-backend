package com.idea2strategy.backend.application.notification;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class EmailNotificationPreferenceService {
    private final EmailNotificationPreferencePort preferences;
    private final Clock clock;

    public EmailNotificationPreferenceService(EmailNotificationPreferencePort preferences, Clock clock) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EmailNotificationPreferenceView get(UUID accountId) {
        return new EmailNotificationPreferenceView(
                preferences.enabled(Objects.requireNonNull(accountId, "accountId")));
    }

    public EmailNotificationPreferenceView replace(UUID accountId, boolean enabled) {
        Objects.requireNonNull(accountId, "accountId");
        preferences.replace(accountId, enabled, clock.instant());
        return new EmailNotificationPreferenceView(enabled);
    }
}
