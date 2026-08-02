package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LifecyclePasswordStepUpServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T08:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION_ID = UUID.fromString("90000000-0000-0000-0000-000000000009");

    @Test
    void authenticatesActivePasswordIdentityWithoutIssuingASession() {
        var commands = mock(IdentityCommandPort.class);
        var service = service(account(AccountLifecycleStatus.ACTIVE), true, commands);

        LifecycleStepUp result = service.authenticate(" Person@Example.com ", "correct-secret", CORRELATION_ID);

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.proof()).isEqualTo(new AccountLifecycleAuthenticationProof(
                AccountLifecycleAuthenticationMethod.PASSWORD,
                ACCOUNT_ID,
                null,
                null,
                NOW,
                NOW,
                true));
        assertThat(result.toString()).doesNotContain("correct-secret").doesNotContain("Person@Example.com");
        verify(commands).recordStepUpSuccess(new AuthenticationSuccess(
                ACCOUNT_ID, account(AccountLifecycleStatus.ACTIVE).loginIdentityId(), CORRELATION_ID, NOW));
    }

    @Test
    void permitsDormantAndClosingAccountsBecauseTheirSessionsCannotBeUsed() {
        assertThat(service(account(AccountLifecycleStatus.DORMANT), true, mock(IdentityCommandPort.class))
                        .authenticate("person@example.com", "correct-secret", CORRELATION_ID).accountId())
                .isEqualTo(ACCOUNT_ID);
        assertThat(service(account(AccountLifecycleStatus.CLOSING), true, mock(IdentityCommandPort.class))
                        .authenticate("person@example.com", "correct-secret", CORRELATION_ID).accountId())
                .isEqualTo(ACCOUNT_ID);
    }

    @Test
    void rejectsInvalidCredentialsWithANonEnumeratingMessage() {
        var commands = mock(IdentityCommandPort.class);
        var found = account(AccountLifecycleStatus.ACTIVE);
        assertThatThrownBy(() -> service(found, false, commands)
                        .authenticate("person@example.com", "wrong-secret", CORRELATION_ID))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("Invalid email or password");
        verify(commands).recordLoginFailure(new LoginFailure(
                ACCOUNT_ID, found.loginIdentityId(), "STEP_UP_INVALID_PASSWORD", CORRELATION_ID, NOW));
        assertThatThrownBy(() -> service(null, true, mock(IdentityCommandPort.class))
                        .authenticate("missing@example.com", "wrong-secret", CORRELATION_ID))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void rejectsInactiveIdentityEmailAndUnsupportedAccountStates() {
        assertRejected(account(AccountLifecycleStatus.PENDING_VERIFICATION));
        assertRejected(account(AccountLifecycleStatus.CLOSED));
        assertRejected(new PasswordLoginAccount(
                ACCOUNT_ID, UUID.randomUUID(), AccountLifecycleStatus.ACTIVE,
                EmailStatus.PENDING_VERIFICATION, LoginIdentityStatus.ACTIVE, "encoded", 1, 1));
        assertRejected(new PasswordLoginAccount(
                ACCOUNT_ID, UUID.randomUUID(), AccountLifecycleStatus.ACTIVE,
                EmailStatus.VERIFIED, LoginIdentityStatus.DISABLED, "encoded", 1, 1));
    }

    private static void assertRejected(PasswordLoginAccount account) {
        var commands = mock(IdentityCommandPort.class);
        assertThatThrownBy(() -> service(account, true, commands)
                        .authenticate("person@example.com", "correct-secret", CORRELATION_ID))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("Invalid email or password");
        verify(commands).recordLoginFailure(new LoginFailure(
                ACCOUNT_ID, account.loginIdentityId(), "STEP_UP_NOT_ELIGIBLE", CORRELATION_ID, NOW));
    }

    private static LifecyclePasswordStepUpService service(
            PasswordLoginAccount account, boolean passwordMatches, IdentityCommandPort commands) {
        IdentityQueryPort identities = ignored -> Optional.ofNullable(account);
        PasswordVerifier passwords = (raw, encoded) -> passwordMatches && raw.equals("correct-secret");
        EmailLookup emails = raw -> raw.strip().toLowerCase();
        return new LifecyclePasswordStepUpService(
                identities, commands, passwords, emails, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PasswordLoginAccount account(AccountLifecycleStatus status) {
        return new PasswordLoginAccount(
                ACCOUNT_ID, UUID.fromString("11000000-0000-0000-0000-000000000001"), status, EmailStatus.VERIFIED,
                LoginIdentityStatus.ACTIVE, "encoded", 1, 1);
    }
}
