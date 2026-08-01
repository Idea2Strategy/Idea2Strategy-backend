package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OidcRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void acceptsOnlyAnExistingActiveSubjectAndDoesNotTrustMatchingEmail() {
        UUID accountId = UUID.randomUUID();
        UUID loginId = UUID.randomUUID();
        var commands = new RecordingCommands();
        var queries = new OidcIdentityQueryPort() {
            @Override
            public Optional<OidcProvider> findProvider(String providerCode) {
                return Optional.of(new OidcProvider((short) 2, "EXAMPLE", "https://issuer.example", true));
            }

            @Override
            public Optional<OidcLoginAccount> findActiveLogin(short providerId, String subjectHmac) {
                return subjectHmac.equals("linked-subject")
                        ? Optional.of(new OidcLoginAccount(
                                accountId, loginId, AccountLifecycleStatus.ACTIVE, LoginIdentityStatus.ACTIVE, 1))
                        : Optional.empty();
            }
        };
        var service = new OidcRecoveryService(
                queries,
                commands,
                ignored -> new ProtectedOidcSubject("linked-subject", (short) 1),
                Clock.fixed(NOW, ZoneOffset.UTC));
        UUID correlationId = UUID.randomUUID();

        assertThat(service.verifyExistingLink(
                        new VerifiedOidcPrincipal(
                                "EXAMPLE", "https://issuer.example", "raw-subject", "matching@example.com"),
                        correlationId))
                .isEqualTo(accountId);
        assertThat(commands.accountId).isEqualTo(accountId);
        assertThat(commands.loginId).isEqualTo(loginId);

        var unlinked = new OidcRecoveryService(
                queries,
                commands,
                ignored -> new ProtectedOidcSubject("not-linked", (short) 1),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> unlinked.verifyExistingLink(
                        new VerifiedOidcPrincipal(
                                "EXAMPLE", "https://issuer.example", "another-subject", "matching@example.com"),
                        UUID.randomUUID()))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("OIDC identity is not linked");
    }

    private static final class RecordingCommands implements AccountRecoveryCommandPort {
        private UUID accountId;
        private UUID loginId;

        @Override
        public void issuePasswordReset(PendingPasswordReset reset) {}

        @Override
        public PasswordResetOutcome consumePasswordReset(PasswordResetConsumption consumption) {
            return PasswordResetOutcome.NOT_FOUND;
        }

        @Override
        public void replaceRecoveryCodes(RecoveryCodeBatch batch) {}

        @Override
        public RecoveryCodeOutcome consumeRecoveryCode(RecoveryCodeConsumption consumption) {
            return RecoveryCodeOutcome.NOT_FOUND;
        }

        @Override
        public void recordOidcRecoveryProof(
                UUID accountId, UUID loginIdentityId, UUID correlationId, Instant verifiedAt) {
            this.accountId = accountId;
            this.loginId = loginIdentityId;
        }
    }
}
