package com.idea2strategy.backend.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.ConfirmOidcLinkCommand;
import com.idea2strategy.backend.application.identity.DuplicateEmailException;
import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import com.idea2strategy.backend.application.identity.EmailRegistrationService;
import com.idea2strategy.backend.application.identity.LoginCommand;
import com.idea2strategy.backend.application.identity.NistPasswordPolicy;
import com.idea2strategy.backend.application.identity.OidcAuthenticationService;
import com.idea2strategy.backend.application.identity.OidcIdentityLinkingService;
import com.idea2strategy.backend.application.identity.OidcLoginCommand;
import com.idea2strategy.backend.application.identity.PasswordHash;
import com.idea2strategy.backend.application.identity.PasswordRecoveryService;
import com.idea2strategy.backend.application.identity.PasswordResetRejectedException;
import com.idea2strategy.backend.application.identity.ProtectedEmail;
import com.idea2strategy.backend.application.identity.ProtectedOidcSubject;
import com.idea2strategy.backend.application.identity.ResendVerificationCommand;
import com.idea2strategy.backend.application.identity.RequestPasswordResetCommand;
import com.idea2strategy.backend.application.identity.RecoverWithCodeCommand;
import com.idea2strategy.backend.application.identity.ResetPasswordCommand;
import com.idea2strategy.backend.application.identity.SessionToken;
import com.idea2strategy.backend.application.identity.SignupCommand;
import com.idea2strategy.backend.application.identity.StartOidcLinkCommand;
import com.idea2strategy.backend.application.identity.VerificationToken;
import com.idea2strategy.backend.application.identity.VerifyEmailCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = IdentityPersistenceIntegrationTest.TestApplication.class)
class IdentityPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-01T12:30:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private IdentityJooqQueryAdapter queryAdapter;

    @Autowired
    private IdentityJpaCommandAdapter commandAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void signupVerificationAndLoginShareOneTransactionalIdentityModel() {
        var tokenSequence = new AtomicInteger();
        var registration = registrationService(tokenSequence);
        var signup = registration.signup(new SignupCommand(
                " Person@Example.com ", "a sufficiently long passphrase", UUID.randomUUID(), "192.0.2.0/24"));

        assertThatThrownBy(() -> authenticationService().login(new LoginCommand(
                        "person@example.com", "a sufficiently long passphrase", "Chrome", UUID.randomUUID())))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("Email verification is required");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.sessions where account_id = ?",
                        Integer.class,
                        signup.accountId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "select failed_attempt_count from identity.login_identities where account_id = ?",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);

        var replacement = registration.resendVerification(
                new ResendVerificationCommand(signup.accountId(), UUID.randomUUID(), "192.0.2.0/24"));
        assertThatThrownBy(() -> registration.verify(
                        new VerifyEmailCommand(signup.verificationToken(), UUID.randomUUID())))
                .hasMessage("Verification token is no longer valid");
        registration.verify(new VerifyEmailCommand(replacement.verificationToken(), UUID.randomUUID()));
        var login = authenticationService().login(new LoginCommand(
                "person@example.com", "a sufficiently long passphrase", "Chrome", UUID.randomUUID()));

        assertThat(login.accountId()).isEqualTo(signup.accountId());
        assertThat(login.sessionToken()).startsWith("raw-session-token-");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.sessions where account_id = ?",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select failed_attempt_count from identity.login_identities where account_id = ?",
                        Integer.class,
                        signup.accountId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "select lifecycle_status::text from identity.accounts where id = ?",
                        String.class,
                        signup.accountId()))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                        """
                        select credential.password_hash
                        from identity.password_credentials credential
                        join identity.login_identities login on login.id = credential.login_identity_id
                        where login.account_id = ?
                        """,
                        String.class,
                        signup.accountId()))
                .isEqualTo("hash:a sufficiently long passphrase");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.authentication_events where account_id = ? and event_type = 'LOGIN_FAILED'",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);

        assertThatThrownBy(() -> registration.verify(
                        new VerifyEmailCommand(replacement.verificationToken(), UUID.randomUUID())))
                .hasMessage("Verification token is no longer valid");
        assertThatThrownBy(() -> registration.signup(new SignupCommand(
                        "person@example.com", "another sufficiently long passphrase", UUID.randomUUID(), null)))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void oidcLinkReplacementRevokesExistingSessionsAndSupportsSubjectOnlyLogin() {
        jdbcTemplate.update("""
                insert into identity.auth_providers
                    (id, code, display_name, provider_type, issuer, is_active)
                values (2, 'EXAMPLE', 'Example OIDC', cast('OIDC' as identity.auth_provider_type),
                        'https://issuer.example', true)
                on conflict (id) do nothing
                """);
        var registration = registrationService(new AtomicInteger());
        var signup = registration.signup(new SignupCommand(
                "oidc-person@example.com",
                "a sufficiently long passphrase",
                UUID.randomUUID(),
                "192.0.2.0/24"));
        registration.verify(new VerifyEmailCommand(signup.verificationToken(), UUID.randomUUID()));
        authenticationService().login(new LoginCommand(
                "oidc-person@example.com",
                "a sufficiently long passphrase",
                "Before switch",
                UUID.randomUUID()));
        UUID passwordLoginId = jdbcTemplate.queryForObject(
                "select id from identity.login_identities where account_id = ? and status = 'ACTIVE'",
                UUID.class,
                signup.accountId());
        UUID resetRequestId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into identity.password_reset_requests
                    (id, account_id, login_identity_id, auth_epoch_at_issue, credential_version_at_issue,
                     token_digest, digest_key_version, requested_at, expires_at)
                values (?, ?, ?, 1, 1, ?, 1, ?, ?)
                """,
                resetRequestId,
                signup.accountId(),
                passwordLoginId,
                "digest:reset:" + resetRequestId,
                NOW.atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(600).atOffset(ZoneOffset.UTC));

        var linking = new OidcIdentityLinkingService(
                queryAdapter,
                commandAdapter,
                ignored -> new ProtectedOidcSubject("hmac:external-subject", (short) 1),
                Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC));
        UUID pendingId = linking.start(new StartOidcLinkCommand(
                signup.accountId(),
                passwordLoginId,
                "EXAMPLE",
                "https://issuer.example",
                "raw-external-subject",
                "oidc-person@example.com",
                UUID.randomUUID()));
        assertThatThrownBy(() -> linking.start(new StartOidcLinkCommand(
                        signup.accountId(),
                        passwordLoginId,
                        "EXAMPLE",
                        "https://issuer.example",
                        "raw-external-subject",
                        "oidc-person@example.com",
                        UUID.randomUUID())))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("OIDC identity is already linked");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.login_identities where account_id = ? and status = 'PENDING'",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);
        var confirmation = new ConfirmOidcLinkCommand(
                signup.accountId(),
                passwordLoginId,
                pendingId,
                "EXAMPLE",
                "https://issuer.example",
                "raw-external-subject",
                "oidc-person@example.com",
                UUID.randomUUID());
        long authEpoch = linking.activate(confirmation);

        assertThat(authEpoch).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "select status::text from identity.login_identities where id = ?",
                        String.class,
                        passwordLoginId))
                .isEqualTo("REPLACED");
        assertThat(jdbcTemplate.queryForObject(
                        "select status::text from identity.login_identities where id = ?",
                        String.class,
                        pendingId))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.sessions where account_id = ? and revoked_at is not null",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select revoked_at is not null from identity.password_reset_requests where id = ?",
                        Boolean.class,
                        resetRequestId))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "select provider_subject_hmac from identity.login_identities where id = ?",
                        String.class,
                        pendingId))
                .isEqualTo("hmac:external-subject");

        var oidcAuthentication = new OidcAuthenticationService(
                queryAdapter,
                commandAdapter,
                ignored -> new ProtectedOidcSubject("hmac:external-subject", (short) 1),
                () -> new SessionToken("raw-oidc-session", "digest:raw-oidc-session"),
                Clock.fixed(NOW.plusSeconds(180), ZoneOffset.UTC));
        var result = oidcAuthentication.login(new OidcLoginCommand(
                "EXAMPLE",
                "https://issuer.example",
                "raw-external-subject",
                "oidc-person@example.com",
                "After switch",
                UUID.randomUUID()));

        assertThat(result.accountId()).isEqualTo(signup.accountId());
        assertThat(jdbcTemplate.queryForObject(
                        "select credential_version_at_issue from identity.sessions where id = ?",
                        Long.class,
                        result.sessionId()))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.authentication_events where account_id = ? and event_type = 'LOGIN_IDENTITY_REPLACED'",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);

        assertThatThrownBy(() -> linking.activate(confirmation))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessage("OIDC link is no longer activatable");
        assertThat(jdbcTemplate.queryForObject(
                        "select auth_epoch from identity.account_security_states where account_id = ?",
                        Long.class,
                        signup.accountId()))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.sessions where account_id = ? and revoked_at is not null",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select revoked_at is not null from identity.password_reset_requests where id = ?",
                        Boolean.class,
                        resetRequestId))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.authentication_events where account_id = ? and event_type = 'LOGIN_IDENTITY_REPLACED'",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);
    }

    @Test
    void concurrentPasswordResetConsumptionSucceedsExactlyOnceAndInvalidatesPriorAccess() throws Exception {
        var registration = registrationService(new AtomicInteger());
        var signup = registration.signup(new SignupCommand(
                "recover-person@example.com",
                "the original sufficiently long passphrase",
                UUID.randomUUID(),
                "192.0.2.0/24"));
        registration.verify(new VerifyEmailCommand(signup.verificationToken(), UUID.randomUUID()));
        authenticationService().login(new LoginCommand(
                "recover-person@example.com",
                "the original sufficiently long passphrase",
                "Before recovery",
                UUID.randomUUID()));

        var recovery = passwordRecoveryService();
        var delivery = recovery.requestPasswordReset(new RequestPasswordResetCommand(
                        "recover-person@example.com", UUID.randomUUID(), "192.0.2.0/24"))
                .orElseThrow();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> resetAfter(start, recovery, delivery.rawToken(),
                    "the first replacement passphrase"));
            var second = executor.submit(() -> resetAfter(start, recovery, delivery.rawToken(),
                    "the second replacement passphrase"));
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }

        assertThat(jdbcTemplate.queryForObject(
                        "select credential_version from identity.password_credentials credential "
                                + "join identity.login_identities login on login.id = credential.login_identity_id "
                                + "where login.account_id = ?",
                        Long.class,
                        signup.accountId()))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                        "select auth_epoch from identity.account_security_states where account_id = ?",
                        Long.class,
                        signup.accountId()))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.sessions where account_id = ? and revoked_at is not null",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.password_reset_requests where account_id = ? and consumed_at is not null",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);
        assertThatThrownBy(() -> authenticationService().login(new LoginCommand(
                        "recover-person@example.com",
                        "the original sufficiently long passphrase",
                        "After recovery",
                        UUID.randomUUID())))
                .isInstanceOf(AuthenticationRejectedException.class);
    }

    @Test
    void recoveryCodeReissueRevokesPreviousSetAndConcurrentConsumptionSucceedsOnce() throws Exception {
        var registration = registrationService(new AtomicInteger());
        var signup = registration.signup(new SignupCommand(
                "codes-person@example.com",
                "the original sufficiently long passphrase",
                UUID.randomUUID(),
                "192.0.2.0/24"));
        registration.verify(new VerifyEmailCommand(signup.verificationToken(), UUID.randomUUID()));
        authenticationService().login(new LoginCommand(
                "codes-person@example.com",
                "the original sufficiently long passphrase",
                "Before recovery",
                UUID.randomUUID()));

        var recovery = passwordRecoveryService();
        var firstSet = recovery.issueRecoveryCodes(signup.accountId(), UUID.randomUUID());
        var replacement = recovery.issueRecoveryCodes(signup.accountId(), UUID.randomUUID());
        assertThat(firstSet.recoveryCodes()).hasSize(10);
        assertThat(replacement.recoveryCodes()).hasSize(10).doesNotContainAnyElementsOf(firstSet.recoveryCodes());
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.recovery_code_sets where account_id = ? and revoked_at is not null",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);

        var start = new CountDownLatch(1);
        String code = replacement.recoveryCodes().getFirst();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> recoverAfter(start, recovery, code, "first code replacement passphrase"));
            var second = executor.submit(() -> recoverAfter(start, recovery, code, "second code replacement passphrase"));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.recovery_codes where used_at is not null and recovery_code_set_id in "
                                + "(select id from identity.recovery_code_sets where account_id = ?)",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from operations.notifications where account_id = ? and notification_type = 'ACCOUNT_RECOVERED'",
                        Integer.class,
                        signup.accountId()))
                .isEqualTo(1);
    }

    private static boolean resetAfter(
            CountDownLatch start, PasswordRecoveryService recovery, String rawToken, String newPassword)
            throws InterruptedException {
        start.await();
        try {
            recovery.resetPassword(new ResetPasswordCommand(rawToken, newPassword, UUID.randomUUID()));
            return true;
        } catch (PasswordResetRejectedException exception) {
            return false;
        }
    }

    private static boolean recoverAfter(
            CountDownLatch start, PasswordRecoveryService recovery, String code, String newPassword)
            throws InterruptedException {
        start.await();
        try {
            recovery.recoverWithCode(new RecoverWithCodeCommand(
                    "codes-person@example.com", code, newPassword, UUID.randomUUID()));
            return true;
        } catch (PasswordResetRejectedException exception) {
            return false;
        }
    }

    private PasswordRecoveryService passwordRecoveryService() {
        var tokenSequence = new AtomicInteger();
        return new PasswordRecoveryService(
                queryAdapter,
                commandAdapter,
                raw -> "lookup:" + raw.trim().toLowerCase(),
                new NistPasswordPolicy(List.of()),
                raw -> new PasswordHash("hash:" + raw, "TEST", "{}"),
                () -> {
                    String raw = "raw-password-recovery-token-" + tokenSequence.incrementAndGet();
                    return new com.idea2strategy.backend.application.identity.PasswordResetToken(
                            raw, "digest:" + raw);
                },
                raw -> "digest:" + raw,
                Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC),
                java.time.Duration.ofMinutes(30));
    }

    private EmailRegistrationService registrationService(AtomicInteger tokenSequence) {
        return new EmailRegistrationService(
                queryAdapter,
                commandAdapter,
                raw -> {
                    String normalized = raw.trim().toLowerCase();
                    return new ProtectedEmail(
                            normalized,
                            "ciphertext:" + normalized,
                            "lookup:" + normalized,
                            (short) 1,
                            (short) 1);
                },
                new NistPasswordPolicy(List.of()),
                raw -> new PasswordHash("hash:" + raw, "TEST", "{}"),
                () -> {
                    String raw = "raw-verification-token-" + tokenSequence.incrementAndGet() + "-" + UUID.randomUUID();
                    return new VerificationToken(raw, "digest:" + raw);
                },
                raw -> "digest:" + raw,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EmailAuthenticationService authenticationService() {
        String token = "raw-session-token-" + UUID.randomUUID();
        return new EmailAuthenticationService(
                queryAdapter,
                commandAdapter,
                (raw, encoded) -> encoded.equals("hash:" + raw),
                raw -> "lookup:" + raw.trim().toLowerCase(),
                () -> new SessionToken(token, "digest:" + token),
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = IdentityAccountJpaEntity.class)
    @Import({IdentityJooqQueryAdapter.class, IdentityJpaCommandAdapter.class})
    static class TestApplication {}
}
