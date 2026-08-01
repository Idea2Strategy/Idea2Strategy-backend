package com.idea2strategy.backend.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.DuplicateEmailException;
import com.idea2strategy.backend.application.identity.EmailAuthenticationService;
import com.idea2strategy.backend.application.identity.EmailRegistrationService;
import com.idea2strategy.backend.application.identity.LoginCommand;
import com.idea2strategy.backend.application.identity.NistPasswordPolicy;
import com.idea2strategy.backend.application.identity.PasswordHash;
import com.idea2strategy.backend.application.identity.ProtectedEmail;
import com.idea2strategy.backend.application.identity.ResendVerificationCommand;
import com.idea2strategy.backend.application.identity.SessionToken;
import com.idea2strategy.backend.application.identity.SignupCommand;
import com.idea2strategy.backend.application.identity.VerificationToken;
import com.idea2strategy.backend.application.identity.VerifyEmailCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
        assertThat(jdbcTemplate.queryForObject("select count(*) from identity.sessions", Integer.class)).isZero();
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
        assertThat(login.sessionToken()).isEqualTo("raw-session-token");
        assertThat(jdbcTemplate.queryForObject("select count(*) from identity.sessions", Integer.class)).isEqualTo(1);
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
                        "select password_hash from identity.password_credentials",
                        String.class))
                .isEqualTo("hash:a sufficiently long passphrase");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.authentication_events where event_type = 'LOGIN_FAILED'",
                        Integer.class))
                .isEqualTo(1);

        assertThatThrownBy(() -> registration.verify(
                        new VerifyEmailCommand(replacement.verificationToken(), UUID.randomUUID())))
                .hasMessage("Verification token is no longer valid");
        assertThatThrownBy(() -> registration.signup(new SignupCommand(
                        "person@example.com", "another sufficiently long passphrase", UUID.randomUUID(), null)))
                .isInstanceOf(DuplicateEmailException.class);
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
                    String raw = "raw-verification-token-" + tokenSequence.incrementAndGet();
                    return new VerificationToken(raw, "digest:" + raw);
                },
                raw -> "digest:" + raw,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EmailAuthenticationService authenticationService() {
        return new EmailAuthenticationService(
                queryAdapter,
                commandAdapter,
                (raw, encoded) -> encoded.equals("hash:" + raw),
                raw -> "lookup:" + raw.trim().toLowerCase(),
                () -> new SessionToken("raw-session-token", "digest:raw-session-token"),
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = IdentityAccountJpaEntity.class)
    @Import({IdentityJooqQueryAdapter.class, IdentityJpaCommandAdapter.class})
    static class TestApplication {}
}
