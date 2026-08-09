package com.idea2strategy.backend.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000018");
    private static final UUID NOTIFICATION = UUID.fromString("20000000-0000-4000-8000-000000000018");
    private static final Instant NOW = Instant.parse("2026-08-02T14:00:00Z");

    @Test
    void userPreferencesCannotDisableMandatoryDeliveryChannels() {
        var store = new RecordingStore();
        var service = service(
                new NotificationPolicy("SECURITY_EVENT", "policy-v1", true,
                        Set.of(NotificationChannel.APP, NotificationChannel.EMAIL)),
                false, store);

        service.create(request("SECURITY_EVENT"));

        assertThat(store.channels).containsExactlyInAnyOrder(NotificationChannel.APP, NotificationChannel.EMAIL);
    }

    @Test
    void optionalEmailOptOutStillKeepsTheOwnedInAppRecord() {
        var store = new RecordingStore();
        var service = service(
                new NotificationPolicy("BOT_SUMMARY", "policy-v1", false,
                        Set.of(NotificationChannel.APP, NotificationChannel.EMAIL)),
                false, store);

        service.create(request("BOT_SUMMARY"));

        assertThat(store.channels).containsExactly(NotificationChannel.APP);
    }

    @Test
    void optionalEmailOptInAddsEmailWhenThePolicyAllowsIt() {
        var store = new RecordingStore();
        var service = service(
                new NotificationPolicy("CASE_UPDATED", "policy-v1", false,
                        Set.of(NotificationChannel.APP, NotificationChannel.EMAIL)),
                true, store);

        service.create(request("CASE_UPDATED"));

        assertThat(store.channels).containsExactlyInAnyOrder(NotificationChannel.APP, NotificationChannel.EMAIL);
    }

    @Test
    void markReadDoesNotRevealAnotherAccountsNotification() {
        var store = new RecordingStore();
        store.owned = false;
        var service = service(
                new NotificationPolicy("SECURITY_EVENT", "policy-v1", true, Set.of(NotificationChannel.APP)),
                false, store);

        assertThatThrownBy(() -> service.markRead(ACCOUNT, NOTIFICATION))
                .isInstanceOf(NotificationUnavailableException.class)
                .hasMessage("NOTIFICATION_NOT_AVAILABLE");
    }

    private NotificationService service(
            NotificationPolicy policy,
            boolean emailEnabled,
            RecordingStore store) {
        return new NotificationService(
                type -> policy,
                new EmailNotificationPreferencePort() {
                    @Override public boolean enabled(UUID accountId) { return emailEnabled; }
                    @Override public void replace(UUID accountId, boolean enabled, Instant updatedAt) {
                        throw new UnsupportedOperationException();
                    }
                },
                store,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private NotificationRequest request(String type) {
        return new NotificationRequest(
                ACCOUNT, type, "template-v1", "ko-KR", "event-1", "hash-1",
                Map.of("safeReference", "case-1"), UUID.randomUUID());
    }

    private static final class RecordingStore implements NotificationStore {
        private Set<NotificationChannel> channels;
        private boolean owned = true;

        @Override
        public NotificationReceipt create(
                NotificationRequest request,
                NotificationPolicy policy,
                Set<NotificationChannel> channels,
                Instant now) {
            this.channels = channels;
            return new NotificationReceipt(NOTIFICATION, channels, false);
        }

        @Override
        public boolean markRead(UUID accountId, UUID notificationId, Instant now) {
            return owned;
        }
    }
}
