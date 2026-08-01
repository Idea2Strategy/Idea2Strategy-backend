package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class ApplicationEventVerificationDeliveryTest {
    @Test
    void publishesSecretsForAnEmailConsumerWithoutExposingThemInLogs() {
        var publisher = mock(ApplicationEventPublisher.class);
        var delivery = new ApplicationEventVerificationDelivery(publisher);
        Instant expiresAt = Instant.parse("2026-08-02T12:00:00Z");

        delivery.send("person@example.com", "verification-secret", expiresAt);

        var captor = ArgumentCaptor.forClass(VerificationEmailRequested.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("person@example.com");
        assertThat(captor.getValue().verificationToken()).isEqualTo("verification-secret");
        assertThat(captor.getValue().toString())
                .doesNotContain("person@example.com")
                .doesNotContain("verification-secret");
    }

    @Test
    void publishesAccountScopedResendWithoutNeedingEmailInTheRequest() {
        var publisher = mock(ApplicationEventPublisher.class);
        var delivery = new ApplicationEventVerificationDelivery(publisher);
        UUID accountId = UUID.randomUUID();

        delivery.send(accountId, "replacement-secret", Instant.parse("2026-08-02T12:00:00Z"));

        var captor = ArgumentCaptor.forClass(AccountVerificationEmailRequested.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().accountId()).isEqualTo(accountId);
        assertThat(captor.getValue().toString()).doesNotContain("replacement-secret");
    }
}
