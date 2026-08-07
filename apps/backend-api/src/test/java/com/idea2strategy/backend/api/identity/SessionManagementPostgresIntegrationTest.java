package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.AuthenticationSession;
import com.idea2strategy.backend.application.identity.AuthenticationSuccess;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import com.idea2strategy.backend.persistence.identity.IdentityAccountJpaEntity;
import com.idea2strategy.backend.persistence.identity.IdentityJooqQueryAdapter;
import com.idea2strategy.backend.persistence.identity.IdentityJpaCommandAdapter;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
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
@SpringBootTest(classes = SessionManagementPostgresIntegrationTest.TestApplication.class)
class SessionManagementPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-02T03:00:00Z");
    private static final short PROVIDER_ID = 91;

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
    private IdentityJooqQueryAdapter queries;

    @Autowired
    private IdentityJpaCommandAdapter commands;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void provider() {
        jdbc.update("""
                insert into identity.auth_providers
                    (id, code, display_name, provider_type, issuer)
                values (?, 'SESSION_TEST', 'Session test', cast('OIDC' as identity.auth_provider_type), 'test')
                on conflict (id) do nothing
                """, PROVIDER_ID);
    }

    @Test
    void validatesListsAndRevokesAnOpaqueSessionWithoutReturningItsDigest() {
        Fixture fixture = fixture();
        commands.createSession(fixture.session());

        var stored = queries.findByTokenDigest(fixture.session().tokenDigest()).orElseThrow();
        assertThat(stored.accountId()).isEqualTo(fixture.accountId());
        assertThat(queries.findActiveByAccountId(fixture.accountId(), NOW))
                .singleElement()
                .satisfies(active -> assertThat(active.sessionId()).isEqualTo(fixture.session().id()));

        String replacementDigest = "digest:" + UUID.randomUUID();
        assertThat(commands.rotate(
                        fixture.accountId(),
                        fixture.session().id(),
                        fixture.session().tokenDigest(),
                        replacementDigest,
                        NOW.plusSeconds(7200),
                        UUID.randomUUID(),
                        NOW.plusSeconds(30)))
                .isTrue();
        assertThat(queries.findByTokenDigest(fixture.session().tokenDigest())).isEmpty();
        assertThat(queries.findByTokenDigest(replacementDigest)).isPresent();

        var service = new SessionManagementService(
                queries, commands, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));
        service.authenticate(replacementDigest, UUID.randomUUID());

        assertThat(commands.revoke(
                        fixture.accountId(),
                        fixture.session().id(),
                        "REMOTE_LOGOUT",
                        UUID.randomUUID(),
                        NOW.plusSeconds(60)))
                .isTrue();
        assertThat(queries.findActiveByAccountId(fixture.accountId(), NOW)).isEmpty();
        assertThatThrownBy(() -> service.authenticate(replacementDigest, UUID.randomUUID()))
                .isInstanceOf(AuthenticationRejectedException.class);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.authentication_events where account_id = ? and event_type = 'SESSION_REVOKED'",
                        Integer.class,
                        fixture.accountId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.authentication_events where account_id = ? and event_type = 'SESSION_VALIDATED'",
                        Integer.class,
                        fixture.accountId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.authentication_events where account_id = ? and event_type = 'SESSION_REJECTED' and reason_code = 'REVOKED'",
                        Integer.class,
                        fixture.accountId()))
                .isEqualTo(1);
    }

    @Test
    void replacesTheOldestActiveSessionAtTheConfiguredLimit() {
        Fixture fixture = fixture();
        commands.createSession(fixture.session());
        var replacement = new AuthenticationSession(
                UUID.randomUUID(),
                fixture.accountId(),
                fixture.loginId(),
                1,
                null,
                "digest:" + UUID.randomUUID(),
                "phone",
                NOW.plusSeconds(10),
                NOW.plusSeconds(3610));
        UUID correlationId = UUID.randomUUID();

        commands.completeLogin(
                replacement,
                new AuthenticationSuccess(
                        fixture.accountId(), fixture.loginId(), correlationId, NOW.plusSeconds(10)),
                1);

        assertThat(queries.findActiveByAccountId(fixture.accountId(), NOW.plusSeconds(10)))
                .singleElement()
                .satisfies(active -> assertThat(active.sessionId()).isEqualTo(replacement.id()));
        assertThat(jdbc.queryForObject(
                        "select revoke_reason_code from identity.sessions where id = ?",
                        String.class,
                        fixture.session().id()))
                .isEqualTo("SESSION_LIMIT_REPLACED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.authentication_events "
                                + "where account_id = ? and event_type = 'SESSION_REVOKED' "
                                + "and reason_code = 'SESSION_LIMIT_REPLACED'",
                        Integer.class,
                        fixture.accountId()))
                .isEqualTo(1);
    }

    @Test
    void concurrentLoginsReplaceInsteadOfRejectingWhileKeepingTheConfiguredLimit() {
        Fixture fixture = fixture();
        var first = fixture.session();
        var second = new AuthenticationSession(
                UUID.randomUUID(),
                fixture.accountId(),
                fixture.loginId(),
                1,
                null,
                "digest:" + UUID.randomUUID(),
                "phone",
                NOW,
                NOW.plusSeconds(3600));

        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> completeLogin(start, fixture, first));
            var secondResult = executor.submit(() -> completeLogin(start, fixture, second));
            start.countDown();
            assertThat(firstResult.get()).isTrue();
            assertThat(secondResult.get()).isTrue();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        assertThat(queries.findActiveByAccountId(fixture.accountId(), NOW)).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.authentication_events "
                                + "where account_id = ? and event_type = 'SESSION_REVOKED' "
                                + "and reason_code = 'SESSION_LIMIT_REPLACED'",
                        Integer.class,
                        fixture.accountId()))
                .isEqualTo(1);

        commands.revokeAll(fixture.accountId(), "LOGOUT_ALL", UUID.randomUUID(), NOW.plusSeconds(1));
        assertThat(jdbc.queryForObject(
                        "select auth_epoch from identity.account_security_states where account_id = ?",
                        Long.class,
                        fixture.accountId()))
                .isEqualTo(2L);
        assertThat(queries.findActiveByAccountId(fixture.accountId(), NOW.plusSeconds(1))).isEmpty();
    }

    private boolean completeLogin(CountDownLatch start, Fixture fixture, AuthenticationSession session)
            throws InterruptedException {
        start.await();
        try {
            commands.completeLogin(
                    session,
                    new AuthenticationSuccess(fixture.accountId(), fixture.loginId(), UUID.randomUUID(), NOW),
                    1);
            return true;
        } catch (AuthenticationRejectedException exception) {
            return false;
        }
    }

    private Fixture fixture() {
        UUID accountId = UUID.randomUUID();
        UUID loginId = UUID.randomUUID();
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status) values (?, cast('ACTIVE' as identity.account_lifecycle_status))",
                accountId);
        jdbc.update("insert into identity.account_security_states (account_id, auth_epoch) values (?, 1)", accountId);
        jdbc.update("""
                insert into identity.login_identities
                    (id, account_id, provider_id, provider_subject_hmac, subject_key_version,
                     status, activated_at)
                values (?, ?, ?, ?, 1, cast('ACTIVE' as identity.login_identity_status), ?)
                """, loginId, accountId, PROVIDER_ID, "subject:" + loginId, NOW.atOffset(ZoneOffset.UTC));
        var session = new AuthenticationSession(
                UUID.randomUUID(),
                accountId,
                loginId,
                1,
                null,
                "digest:" + UUID.randomUUID(),
                "laptop",
                NOW,
                NOW.plusSeconds(3600));
        return new Fixture(accountId, loginId, session);
    }

    private record Fixture(UUID accountId, UUID loginId, AuthenticationSession session) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = IdentityAccountJpaEntity.class)
    @Import({IdentityJooqQueryAdapter.class, IdentityJpaCommandAdapter.class})
    static class TestApplication {}
}
