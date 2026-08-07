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
import java.time.Duration;
import com.idea2strategy.backend.domain.identity.AccountPreferenceDefaults;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import org.junit.jupiter.api.Test;

class OidcAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final OidcProvider PROVIDER =
            new OidcProvider((short) 2, "EXAMPLE", "https://issuer.example", true);

    @Test
    void unlinkedSubjectIsRejectedWithoutFallingBackToProviderEmail() {
        var queries = new StubQueries(PROVIDER, null);
        var commands = new RecordingCommands();
        var service = service(queries, commands, principal -> new ProtectedOidcSubject("subject-hmac", (short) 1));

        assertThatThrownBy(() -> service.login(new OidcLoginCommand(
                        "EXAMPLE",
                        "https://issuer.example",
                        "raw-provider-subject",
                        "same-as-existing@example.com",
                        "Chrome",
                        UUID.randomUUID())))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("OIDC identity is not linked");

        assertThat(commands.sessions).isEmpty();
        assertThat(queries.lookedUpSubjects).containsExactly("subject-hmac");
    }

    @Test
    void issuerMismatchIsRejectedBeforeSubjectIsProtectedOrLookedUp() {
        var queries = new StubQueries(PROVIDER, null);
        var commands = new RecordingCommands();
        var protectedSubjects = new ArrayList<VerifiedOidcPrincipal>();
        var service = service(queries, commands, principal -> {
            protectedSubjects.add(principal);
            return new ProtectedOidcSubject("subject-hmac", (short) 1);
        });

        assertThatThrownBy(() -> service.login(new OidcLoginCommand(
                        "EXAMPLE",
                        "https://attacker.example",
                        "raw-provider-subject",
                        "person@example.com",
                        null,
                        UUID.randomUUID())))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("OIDC provider is not trusted");

        assertThat(protectedSubjects).isEmpty();
        assertThat(queries.lookedUpSubjects).isEmpty();
    }

    @Test
    void linkedSubjectCreatesPasswordIndependentSessionWithoutLeakingRawSubject() {
        UUID accountId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID loginIdentityId = UUID.fromString("12000000-0000-4000-8000-000000000001");
        var account = new OidcLoginAccount(
                accountId,
                loginIdentityId,
                AccountLifecycleStatus.ACTIVE,
                LoginIdentityStatus.ACTIVE,
                4);
        var queries = new StubQueries(PROVIDER, account);
        var commands = new RecordingCommands();
        var service = service(queries, commands, principal -> new ProtectedOidcSubject("subject-hmac", (short) 7));

        LoginResult result = service.login(new OidcLoginCommand(
                "EXAMPLE",
                "https://issuer.example",
                "raw-provider-subject",
                "person@example.com",
                "Chrome",
                UUID.fromString("13000000-0000-4000-8000-000000000001")));

        assertThat(result.refreshTokenSecret()).isEqualTo("session-token");
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(commands.sessions).singleElement().satisfies(session -> {
            assertThat(session.accountId()).isEqualTo(accountId);
            assertThat(session.loginIdentityId()).isEqualTo(loginIdentityId);
            assertThat(session.authEpoch()).isEqualTo(4);
            assertThat(session.credentialVersion()).isNull();
        });
        assertThat(commands.sessions.toString()).doesNotContain("raw-provider-subject");
    }

    @Test
    void verifiedUnboundEmailCreatesANewOidcAccountButNeverAutoLinksAnExistingEmail() {
        var queries = new StubQueries(PROVIDER, null);
        var sessions = new RecordingCommands();
        var registrations = new RecordingOidcCommands();
        var protectedEmail = new ProtectedEmail(
                "person@example.com", "ciphertext", "email-hmac", (short) 1, (short) 1);
        var service = new OidcAuthenticationService(
                queries,
                sessions,
                principal -> new ProtectedOidcSubject("subject-hmac", (short) 1),
                () -> new SessionToken("session-token", "session-digest"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(12),
                lookup -> false,
                registrations,
                raw -> protectedEmail,
                new AccountPreferenceDefaults("ko", "America/New_York", ThemePreference.SYSTEM));

        LoginResult result = service.login(new OidcLoginCommand(
                "EXAMPLE", "https://issuer.example", "new-subject", "person@example.com",
                "Chrome", UUID.randomUUID()));

        assertThat(registrations.registration).isNotNull();
        assertThat(registrations.registration.accountId()).isEqualTo(result.accountId());
        assertThat(sessions.sessions).hasSize(1);

        var existingEmailService = new OidcAuthenticationService(
                queries,
                sessions,
                principal -> new ProtectedOidcSubject("another-subject", (short) 1),
                () -> new SessionToken("session-token-2", "session-digest-2"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(12),
                lookup -> true,
                new RecordingOidcCommands(),
                raw -> protectedEmail,
                new AccountPreferenceDefaults("ko", "America/New_York", ThemePreference.SYSTEM));
        assertThatThrownBy(() -> existingEmailService.login(new OidcLoginCommand(
                        "EXAMPLE", "https://issuer.example", "other-subject", "person@example.com",
                        "Chrome", UUID.randomUUID())))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessageContaining("explicit linking is required");
    }

    private static OidcAuthenticationService service(
            OidcIdentityQueryPort queries,
            IdentityCommandPort commands,
            OidcSubjectProtector subjectProtector) {
        return new OidcAuthenticationService(
                queries,
                commands,
                subjectProtector,
                () -> new SessionToken("session-token", "session-digest"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class StubQueries implements OidcIdentityQueryPort {
        private final OidcProvider provider;
        private final OidcLoginAccount account;
        private final List<String> lookedUpSubjects = new ArrayList<>();

        private StubQueries(OidcProvider provider, OidcLoginAccount account) {
            this.provider = provider;
            this.account = account;
        }

        @Override
        public Optional<OidcProvider> findProvider(String providerCode) {
            return Optional.ofNullable(provider);
        }

        @Override
        public Optional<OidcLoginAccount> findActiveLogin(short providerId, String subjectHmac) {
            lookedUpSubjects.add(subjectHmac);
            return Optional.ofNullable(account);
        }
    }

    private static final class RecordingCommands implements IdentityCommandPort {
        private final List<AuthenticationSession> sessions = new ArrayList<>();

        @Override
        public void createSession(AuthenticationSession session) {
            sessions.add(session);
        }

        @Override
        public void recordLoginFailure(LoginFailure failure) {}
    }

    private static final class RecordingOidcCommands implements OidcIdentityCommandPort {
        private PendingOidcRegistration registration;

        @Override
        public void createActiveRegistration(PendingOidcRegistration registration) {
            this.registration = registration;
        }

        @Override
        public void createPendingLink(PendingOidcLink link) {}

        @Override
        public long activatePendingLink(ActivateOidcLink command) {
            return 1;
        }
    }
}
