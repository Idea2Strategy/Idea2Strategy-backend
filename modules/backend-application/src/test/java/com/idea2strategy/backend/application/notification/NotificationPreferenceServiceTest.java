package com.idea2strategy.backend.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationPreferenceServiceTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000018");
    private static final Instant NOW = Instant.parse("2026-08-03T01:00:00Z");

    @Test
    void replacesOnlyTheAuthenticatedAccountsOptionalChannels() {
        var port = new RecordingPort();
        var service = service(false, port);

        var result = service.replace(ACCOUNT, "BOT_SUMMARY", Set.of(NotificationChannel.APP));

        assertThat(result.enabledChannels()).containsExactly(NotificationChannel.APP);
        assertThat(port.accountId).isEqualTo(ACCOUNT);
        assertThat(port.updatedAt).isEqualTo(NOW);
    }

    @Test
    void mandatoryChannelsAndTheInAppInboxCannotBeDisabled() {
        var service = service(true, new RecordingPort());

        assertThatThrownBy(() -> service.replace(
                ACCOUNT, "BOT_SUMMARY", Set.of(NotificationChannel.APP)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replace(
                ACCOUNT, "BOT_SUMMARY", Set.of(NotificationChannel.EMAIL)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private NotificationPreferenceService service(boolean mandatory, RecordingPort port) {
        return new NotificationPreferenceService(
                type -> new NotificationPolicy(
                        type, "policy-v1", mandatory,
                        Set.of(NotificationChannel.APP, NotificationChannel.EMAIL)),
                port,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class RecordingPort implements NotificationPreferenceManagementPort {
        UUID accountId;
        Instant updatedAt;

        @Override
        public List<NotificationPreferenceView> list(UUID accountId) {
            return List.of();
        }

        @Override
        public void replace(
                UUID accountId,
                String typeCode,
                String policyVersion,
                Set<NotificationChannel> enabledChannels,
                Instant updatedAt) {
            this.accountId = accountId;
            this.updatedAt = updatedAt;
        }
    }
}
