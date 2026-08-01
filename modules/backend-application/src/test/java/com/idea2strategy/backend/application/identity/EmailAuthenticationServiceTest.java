package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T11:30:00Z");

    @Test
    void unverifiedAccountDoesNotReceiveSessionEvenWithCorrectPassword() {
        UUID accountId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID loginIdentityId = UUID.fromString("11000000-0000-4000-8000-000000000001");
        var query = new StubQueryPort(new PasswordLoginAccount(
                accountId,
                loginIdentityId,
                AccountLifecycleStatus.PENDING_VERIFICATION,
                EmailStatus.PENDING_VERIFICATION,
                LoginIdentityStatus.PENDING,
                "encoded-password",
                1,
                1));
        var commands = new RecordingCommandPort();
        var service = new EmailAuthenticationService(
                query,
                commands,
                (raw, encoded) -> raw.equals("correct-password") && encoded.equals("encoded-password"),
                rawEmail -> "lookup:" + rawEmail.trim().toLowerCase(),
                () -> new SessionToken("session-token", "session-digest"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.login(new LoginCommand(
                        "person@example.com", "correct-password", "Chrome", UUID.randomUUID())))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("Email verification is required");

        assertThat(commands.sessions).isEmpty();
        assertThat(commands.failures)
                .singleElement()
                .extracting(LoginFailure::reasonCode)
                .isEqualTo("EMAIL_NOT_VERIFIED");
    }

    private record StubQueryPort(PasswordLoginAccount account) implements IdentityQueryPort {
        @Override
        public Optional<PasswordLoginAccount> findPasswordLoginByEmailLookup(String emailLookup) {
            return Optional.ofNullable(account);
        }
    }

    private static final class RecordingCommandPort implements IdentityCommandPort {
        private final List<AuthenticationSession> sessions = new ArrayList<>();
        private final List<LoginFailure> failures = new ArrayList<>();

        @Override
        public void createSession(AuthenticationSession session) {
            sessions.add(session);
        }

        @Override
        public void recordLoginFailure(LoginFailure failure) {
            failures.add(failure);
        }
    }
}
