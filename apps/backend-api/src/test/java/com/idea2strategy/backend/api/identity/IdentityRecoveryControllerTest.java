package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.PasswordRecoveryService;
import com.idea2strategy.backend.application.identity.PasswordResetDelivery;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityRecoveryControllerTest {
    @Test
    void resetRequestReturnsTheSameBodyForExistingAndUnknownAccountsWithoutExposingTheToken() {
        var recovery = mock(PasswordRecoveryService.class);
        var delivery = mock(PasswordResetDeliveryPort.class);
        var sessions = mock(SessionManagementService.class);
        var sessionTokens = new HmacSessionTokens(new byte[32]);
        var controller = new IdentityRecoveryController(recovery, delivery, sessions, sessionTokens);
        UUID accountId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-02T00:30:00Z");
        when(recovery.requestPasswordReset(any()))
                .thenReturn(Optional.of(new PasswordResetDelivery(accountId, "raw-reset-secret", expiresAt)))
                .thenReturn(Optional.empty());

        var existing = controller.requestPasswordReset(
                new IdentityRecoveryController.PasswordResetRequestRequest("existing@example.com"),
                UUID.randomUUID().toString(),
                "192.0.2.0/24");
        var unknown = controller.requestPasswordReset(
                new IdentityRecoveryController.PasswordResetRequestRequest("unknown@example.com"),
                UUID.randomUUID().toString(),
                "192.0.2.0/24");

        assertThat(existing.getStatusCode().value()).isEqualTo(202);
        assertThat(unknown.getStatusCode().value()).isEqualTo(202);
        assertThat(existing.getBody()).isEqualTo(unknown.getBody());
        assertThat(existing.getBody().toString()).doesNotContain("raw-reset-secret");
        verify(delivery).send(accountId, "raw-reset-secret", expiresAt);
    }

    @Test
    void unknownAccountDoesNotAttemptSecretDelivery() {
        var recovery = mock(PasswordRecoveryService.class);
        var delivery = mock(PasswordResetDeliveryPort.class);
        when(recovery.requestPasswordReset(any())).thenReturn(Optional.empty());
        var controller = new IdentityRecoveryController(
                recovery,
                delivery,
                mock(SessionManagementService.class),
                new HmacSessionTokens(new byte[32]));

        controller.requestPasswordReset(
                new IdentityRecoveryController.PasswordResetRequestRequest("unknown@example.com"),
                null,
                null);

        verify(delivery, never()).send(any(), any(), any());
    }
}
