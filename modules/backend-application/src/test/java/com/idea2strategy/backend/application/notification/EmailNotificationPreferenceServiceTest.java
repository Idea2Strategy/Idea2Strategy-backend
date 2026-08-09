package com.idea2strategy.backend.application.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailNotificationPreferenceServiceTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000018");
    private static final Instant NOW = Instant.parse("2026-08-09T09:00:00Z");

    @Test
    void returnsTheStoredAccountPreferenceWithoutInternalPolicyDetails() {
        var port = new RecordingPort();
        port.enabled = false;
        var service = service(port);

        assertThat(service.get(ACCOUNT).enabled()).isFalse();
        assertThat(port.readAccountId).isEqualTo(ACCOUNT);
    }

    @Test
    void replacesOnlyTheRequestedAccountsPreference() {
        var port = new RecordingPort();
        var service = service(port);

        var result = service.replace(ACCOUNT, true);

        assertThat(result.enabled()).isTrue();
        assertThat(port.updatedAccountId).isEqualTo(ACCOUNT);
        assertThat(port.updatedEnabled).isTrue();
        assertThat(port.updatedAt).isEqualTo(NOW);
    }

    private EmailNotificationPreferenceService service(RecordingPort port) {
        return new EmailNotificationPreferenceService(port, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class RecordingPort implements EmailNotificationPreferencePort {
        UUID readAccountId;
        UUID updatedAccountId;
        boolean enabled;
        boolean updatedEnabled;
        Instant updatedAt;

        @Override
        public boolean enabled(UUID accountId) {
            readAccountId = accountId;
            return enabled;
        }

        @Override
        public void replace(UUID accountId, boolean enabled, Instant updatedAt) {
            updatedAccountId = accountId;
            updatedEnabled = enabled;
            this.enabled = enabled;
            this.updatedAt = updatedAt;
        }
    }
}
