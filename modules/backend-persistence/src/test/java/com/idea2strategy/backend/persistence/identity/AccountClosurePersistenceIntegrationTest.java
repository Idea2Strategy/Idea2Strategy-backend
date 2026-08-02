package com.idea2strategy.backend.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.accountclosure.AccountClosureCandidate;
import com.idea2strategy.backend.application.accountclosure.ClosureDomain;
import com.idea2strategy.backend.application.accountclosure.ClosureReadiness;
import com.idea2strategy.backend.application.accountclosure.ClosureReadinessStatus;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommandType;
import com.idea2strategy.backend.application.identity.AccountLifecycleMutation;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AccountClosurePersistenceIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AccountClosurePersistenceIntegrationTest {
    private static final Instant REQUESTED = Instant.parse("2026-08-03T00:00:00Z");
    private static final Instant DEADLINE = REQUESTED.plusSeconds(30L * 24 * 3600);

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

    @Autowired AccountLifecycleJpaCommandAdapter lifecycle;
    @Autowired AccountClosureJpaStore closure;
    @Autowired JdbcTemplate jdbc;

    @Test
    void closesOnlyWithFiveReadyBoundariesAndCreatesPolicySnapshotAndQuarantineAtomically() {
        UUID accountId = UUID.randomUUID();
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", accountId);
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        jdbc.update("""
                insert into identity.account_emails
                    (account_id, email_ciphertext, email_lookup_hmac, email_lookup_key_version,
                     encryption_key_version, status, verified_at)
                values (?, 'ciphertext', ?, 1, 1, 'VERIFIED', ?)
                """, accountId, "hmac-" + accountId, REQUESTED.atOffset(ZoneOffset.UTC));
        lifecycle.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "close-test", "request-hash", UUID.randomUUID(), ignored -> Optional.of(
                        new AccountLifecycleMutation(AccountLifecycleStatus.CLOSING, REQUESTED,
                                AccountLifecycleStatus.ACTIVE, REQUESTED, DEADLINE, "WITHDRAWAL_REQUESTED")));
        jdbc.update("""
                insert into identity.account_retention_policy_versions
                    (version, effective_from, approved_at, approved_by, basis_reference)
                values ('PARTIAL-TEST', ?, ?, 'test', 'partial-policy-test')
                """, REQUESTED.atOffset(ZoneOffset.UTC), REQUESTED.atOffset(ZoneOffset.UTC));
        jdbc.update("""
                insert into identity.account_retention_policy_rules
                    (policy_version, data_category, disposition, retention_days, legal_basis_code)
                values ('PARTIAL-TEST', 'PROFILE', 'ANONYMIZE', 0, 'partial-policy-test')
                """);

        var candidate = new AccountClosureCandidate(accountId, DEADLINE, 2);
        UUID correlationId = UUID.randomUUID();
        long staleGeneration = closure.beginAttempt(candidate, correlationId, DEADLINE.minusSeconds(2));
        long wrongMappingGeneration = closure.beginAttempt(candidate, correlationId, DEADLINE.minusSeconds(1));
        assertThatThrownBy(() -> closure.recordReadiness(accountId, correlationId, staleGeneration,
                new ClosureReadiness(ClosureDomain.BOT, ClosureReadinessStatus.FROZEN,
                        "STALE", "{}", DEADLINE)))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("Stale account closure readiness generation");
        for (var domain : ClosureDomain.values()) {
            closure.recordReadiness(accountId, correlationId, wrongMappingGeneration,
                    new ClosureReadiness(domain, ClosureReadinessStatus.FROZEN,
                            "WRONG_MAPPING", "{}", DEADLINE));
        }
        assertThat(closure.closeIfReady(candidate, correlationId, wrongMappingGeneration,
                "wrong:" + accountId, DEADLINE)).isFalse();

        long generation = closure.beginAttempt(candidate, correlationId, DEADLINE);
        for (var domain : ClosureDomain.values()) {
            closure.recordReadiness(accountId, correlationId, generation,
                    new ClosureReadiness(domain,
                            domain == ClosureDomain.TRADING
                                    ? ClosureReadinessStatus.SETTLED
                                    : ClosureReadinessStatus.FROZEN,
                            "READY", "{}", DEADLINE));
        }
        closure.recordReadiness(accountId, correlationId, generation,
                new ClosureReadiness(ClosureDomain.BOT, ClosureReadinessStatus.FROZEN,
                        "IDEMPOTENT_RETRY", "{}", DEADLINE));

        assertThat(closure.closeIfReady(candidate, correlationId, generation,
                "close:" + accountId, DEADLINE)).isTrue();
        assertThat(jdbc.queryForObject(
                "select cast(lifecycle_status as text) from identity.accounts where id = ?",
                String.class, accountId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.account_retention_obligations where account_id = ?",
                Integer.class, accountId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account_retention_obligations
                where account_id = ? and status = 'FAILED'
                  and failure_code = 'RETENTION_POLICY_MISSING'
                """, Integer.class, accountId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account_closure_readiness
                where correlation_id = ? and generation = ?
                """, Integer.class, correlationId, generation)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account_lifecycle_events
                where account_id = ? and command_type = 'ACCOUNT_CLOSED'
                  and retention_policy_version is null
                """, Integer.class, accountId)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.account_identifier_quarantines where account_id = ?",
                Integer.class, accountId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select cast(status as text) from identity.account_emails where account_id = ?",
                String.class, accountId)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account_lifecycle_command_receipts receipt
                join identity.account_lifecycle_events event on event.id = receipt.lifecycle_event_id
                where receipt.account_id = ? and receipt.command_type = 'ACCOUNT_CLOSED'
                  and event.command_type = 'ACCOUNT_CLOSED' and event.new_status = 'CLOSED'
                """, Integer.class, accountId)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from operations.outbox_messages
                where aggregate_id = ? and event_type = 'ACCOUNT_ACCESS_REVOKED'
                  and payload_document ->> 'cause' = 'ACCOUNT_CLOSED'
                """, Integer.class, accountId)).isOne();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackReceiptEventOutboxAndArtifactsWhenCloseSideEffectsFail() {
        UUID accountId = UUID.randomUUID();
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", accountId);
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        lifecycle.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "rollback-request", "rollback-request-hash", UUID.randomUUID(), ignored -> Optional.of(
                        new AccountLifecycleMutation(AccountLifecycleStatus.CLOSING, REQUESTED,
                                AccountLifecycleStatus.ACTIVE, REQUESTED, DEADLINE, "WITHDRAWAL_REQUESTED")));
        jdbc.update("delete from identity.account_security_states where account_id = ?", accountId);

        var candidate = new AccountClosureCandidate(accountId, DEADLINE, 2);
        UUID correlationId = UUID.randomUUID();
        long generation = closure.beginAttempt(candidate, correlationId, DEADLINE);
        for (var domain : ClosureDomain.values()) {
            closure.recordReadiness(accountId, correlationId, generation,
                    new ClosureReadiness(domain,
                            domain == ClosureDomain.TRADING
                                    ? ClosureReadinessStatus.SETTLED
                                    : ClosureReadinessStatus.FROZEN,
                            "READY", "{}", DEADLINE));
        }

        assertThatThrownBy(() -> closure.closeIfReady(candidate, correlationId, generation,
                "rollback-close:" + accountId, DEADLINE))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("Account security state is missing");
        assertThat(jdbc.queryForObject(
                "select cast(lifecycle_status as text) from identity.accounts where id = ?",
                String.class, accountId)).isEqualTo("CLOSING");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account_lifecycle_events
                where account_id = ? and command_type = 'ACCOUNT_CLOSED'
                """, Integer.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account_lifecycle_command_receipts
                where account_id = ? and command_type = 'ACCOUNT_CLOSED'
                """, Integer.class, accountId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.account_retention_obligations where account_id = ?",
                Integer.class, accountId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from operations.outbox_messages
                where aggregate_id = ? and payload_document ->> 'cause' = 'ACCOUNT_CLOSED'
                """, Integer.class, accountId)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void closingAccountCannotCreateNewNotificationOrIntegrationActivity() {
        UUID accountId = UUID.randomUUID();
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", accountId);
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        lifecycle.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "gate-request", "gate-request-hash", UUID.randomUUID(), ignored -> Optional.of(
                        new AccountLifecycleMutation(AccountLifecycleStatus.CLOSING, REQUESTED,
                                AccountLifecycleStatus.ACTIVE, REQUESTED, DEADLINE, "WITHDRAWAL_REQUESTED")));

        assertThatThrownBy(() -> jdbc.update("""
                insert into operations.notification_preferences
                    (account_id, event_type, channel, enabled, updated_at)
                values (?, 'TEST', 'EMAIL', true, ?)
                """, accountId, REQUESTED.atOffset(ZoneOffset.UTC)))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("account is not ACTIVE");
        assertThatThrownBy(() -> jdbc.update("""
                insert into operations.account_integrations
                    (account_id, integration_code, status)
                values (?, 'TEST', 'ACTIVE')
                """, accountId))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("account is not ACTIVE");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AccountLifecycleJpaCommandAdapter.class, AccountClosureJpaStore.class})
    static class TestApplication {}
}
