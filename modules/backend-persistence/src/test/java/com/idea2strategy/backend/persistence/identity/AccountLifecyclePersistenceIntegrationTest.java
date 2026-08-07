package com.idea2strategy.backend.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.identity.AccountLifecycleCommandType;
import com.idea2strategy.backend.application.identity.AccountLifecycleDecision;
import com.idea2strategy.backend.application.identity.AccountLifecycleMutation;
import com.idea2strategy.backend.application.identity.AccountLifecycleRejectedException;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
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
@SpringBootTest(classes = AccountLifecyclePersistenceIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AccountLifecyclePersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-02T06:00:00Z");

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
    private AccountLifecycleJpaCommandAdapter commands;

    @Autowired
    private AccountLifecycleJooqQueryAdapter queries;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void atomicallyAppendsTheAccountScopedHeadAndReplaysOnlyTheSameRequestHash() {
        UUID accountId = activeAccount(NOW.minusSeconds(60));
        UUID correlationId = UUID.randomUUID();
        Instant deadline = NOW.plusSeconds(30L * 24 * 3600);
        var decision = decision(AccountLifecycleStatus.CLOSING, NOW, AccountLifecycleStatus.ACTIVE, NOW, deadline,
                "WITHDRAWAL_REQUESTED");

        var first = commands.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "withdrawal-1", "hash-1", correlationId, decision);
        var replay = commands.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "withdrawal-1", "hash-1", correlationId, ignored -> {
                    throw new AssertionError("an idempotent replay must not evaluate the decision again");
                });

        assertThat(replay).isEqualTo(first);
        assertThat(first.status()).isEqualTo(AccountLifecycleStatus.CLOSING);
        assertThat(first.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.account_lifecycle_events where account_id = ?",
                        Integer.class, accountId))
                .isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                        select event.previous_event_id, account.last_lifecycle_event_id,
                               event.id, event.request_hash, event.cancellation_deadline_at
                        from identity.account_lifecycle_events event
                        join identity.accounts account on account.id = event.account_id
                        where event.account_id = ? and event.lifecycle_version = 2
                        """, accountId))
                .containsEntry("request_hash", "hash-1")
                .satisfies(row -> {
                    assertThat(row.get("previous_event_id")).isNotNull();
                    assertThat(row.get("last_lifecycle_event_id")).isEqualTo(row.get("id"));
                    assertThat(instant(row.get("cancellation_deadline_at")))
                            .isEqualTo(deadline);
                });
        assertThat(jdbc.queryForMap("""
                        select owner_domain, aggregate_id, aggregate_sequence, event_type,
                               event_schema_version, payload_document
                        from operations.outbox_messages
                        where aggregate_id = ?
                        """, accountId))
                .containsEntry("owner_domain", "identity")
                .containsEntry("aggregate_id", accountId)
                .containsEntry("aggregate_sequence", 2L)
                .containsEntry("event_type", "ACCOUNT_ACCESS_REVOKED")
                .containsEntry("event_schema_version", "account-lifecycle.v1")
                .satisfies(row -> assertThat(row.get("payload_document").toString())
                        .contains(accountId.toString(), correlationId.toString(), "CLOSING", "WITHDRAWAL_REQUESTED")
                        .doesNotContain("reasonCode", "credential"));
        assertThat(jdbc.queryForMap("""
                        select response_status, response_code, lifecycle_event_id, completed_at, response_document
                        from identity.account_lifecycle_command_receipts
                        where account_id = ? and command_type = 'WITHDRAWAL_REQUESTED'
                          and idempotency_key = 'withdrawal-1'
                        """, accountId))
                .containsEntry("response_code", null)
                .satisfies(row -> {
                    assertThat(((Number) row.get("response_status")).intValue()).isEqualTo(202);
                    assertThat(row.get("lifecycle_event_id")).isNotNull();
                    assertThat(instant(row.get("completed_at"))).isEqualTo(NOW);
                    assertThat(row.get("response_document").toString())
                            .contains("CLOSING", "\"applied\": true");
                });

        assertThatThrownBy(() -> commands.executeAtomically(accountId,
                        AccountLifecycleCommandType.REQUEST_WITHDRAWAL, "withdrawal-1", "different-hash",
                        correlationId, decision))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void replaysTheOriginalSkippedClosingReceiptAfterTheAccountReturnsToActive() {
        UUID accountId = activeAccount(NOW.minusSeconds(60));
        Instant deadline = NOW.plusSeconds(30L * 24 * 3600);
        commands.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "withdrawal-applied", "hash-applied", UUID.randomUUID(),
                decision(AccountLifecycleStatus.CLOSING, NOW, AccountLifecycleStatus.ACTIVE, NOW, deadline,
                        "WITHDRAWAL_REQUESTED"));

        var skipped = commands.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "withdrawal-skipped", "hash-skipped", UUID.randomUUID(), ignored -> Optional.empty());

        commands.executeAtomically(accountId, AccountLifecycleCommandType.CANCEL_WITHDRAWAL,
                "cancellation-applied", "hash-cancel", UUID.randomUUID(),
                decision(AccountLifecycleStatus.ACTIVE, NOW.plusSeconds(60), null, null, null,
                        "WITHDRAWAL_CANCELLED"));
        var replay = commands.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "withdrawal-skipped", "hash-skipped", UUID.randomUUID(), ignored -> {
                    throw new AssertionError("a receipt replay must not evaluate the decision again");
                });

        assertThat(skipped.status()).isEqualTo(AccountLifecycleStatus.CLOSING);
        assertThat(skipped.version()).isEqualTo(2);
        assertThat(skipped.applied()).isFalse();
        assertThat(skipped.withdrawalRequestedAt()).isEqualTo(NOW);
        assertThat(skipped.cancellationDeadlineAt()).isEqualTo(deadline);
        assertThat(replay).isEqualTo(skipped);
        assertThat(jdbc.queryForMap("""
                        select response_status, response_code, lifecycle_event_id, response_document
                        from identity.account_lifecycle_command_receipts
                        where account_id = ? and command_type = 'WITHDRAWAL_REQUESTED'
                          and idempotency_key = 'withdrawal-skipped'
                        """, accountId))
                .containsEntry("response_code", null)
                .containsEntry("lifecycle_event_id", null)
                .satisfies(row -> {
                    assertThat(((Number) row.get("response_status")).intValue()).isEqualTo(202);
                    assertThat(row.get("response_document").toString())
                            .contains("CLOSING", "\"applied\": false");
                });

        assertThatThrownBy(() -> commands.executeAtomically(accountId,
                        AccountLifecycleCommandType.REQUEST_WITHDRAWAL, "withdrawal-skipped", "different-hash",
                        UUID.randomUUID(), ignored -> Optional.empty()))
                .isInstanceOf(AccountLifecycleRejectedException.class)
                .hasMessage("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void invalidatesTheAuthEpochAndEveryOpenSessionForSecuritySensitiveTransitions() {
        UUID accountId = activeAccount(NOW.minusSeconds(400L * 24 * 3600));
        UUID sessionId = openSession(accountId);

        commands.executeAtomically(accountId, AccountLifecycleCommandType.MARK_DORMANT,
                "dormancy-1", "dormancy-hash", UUID.randomUUID(),
                decision(AccountLifecycleStatus.DORMANT, NOW, null, null, null, "ACCOUNT_DORMANT"));

        assertThat(jdbc.queryForObject(
                        "select auth_epoch from identity.account_security_states where account_id = ?",
                        Long.class, accountId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForMap("select revoked_at, revoke_reason_code from identity.refresh_token_families where id = ?", sessionId))
                .containsEntry("revoke_reason_code", "ACCOUNT_DORMANT")
                .satisfies(row -> assertThat(row.get("revoked_at")).isNotNull());
    }

    @Test
    void returnsAConservativeOrderedDormancyCandidateWindowForAtomicRechecking() {
        Instant candidateCutoff = Instant.parse("2025-08-05T06:00:00Z");
        UUID before = activeAccount(candidateCutoff.minusSeconds(1));
        UUID atCutoff = activeAccount(candidateCutoff);
        activeAccount(candidateCutoff.plusSeconds(1));

        assertThat(queries.findActiveDormancyCandidates(candidateCutoff, 10))
                .extracting(snapshot -> snapshot.accountId())
                .containsExactly(before, atCutoff);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackTheEventProjectionAndOutboxWhenSecurityInvalidationCannotComplete() {
        UUID accountId = accountWithoutSecurityState(NOW.minusSeconds(60));

        assertThatThrownBy(() -> commands.executeAtomically(accountId,
                        AccountLifecycleCommandType.REQUEST_WITHDRAWAL, "rollback-1", "rollback-hash",
                        UUID.randomUUID(), decision(AccountLifecycleStatus.CLOSING, NOW,
                                AccountLifecycleStatus.ACTIVE, NOW, NOW.plusSeconds(30L * 24 * 3600),
                                "WITHDRAWAL_REQUESTED")))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("Account security state is missing");

        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.account_lifecycle_events where account_id = ?",
                        Integer.class, accountId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages where aggregate_id = ?",
                        Integer.class, accountId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select cast(lifecycle_status as text) from identity.accounts where id = ?",
                        String.class, accountId))
                .isEqualTo("ACTIVE");
    }

    private AccountLifecycleDecision decision(
            AccountLifecycleStatus status,
            Instant occurredAt,
            AccountLifecycleStatus previous,
            Instant requestedAt,
            Instant deadline,
            String reason) {
        var mutation = new AccountLifecycleMutation(status, occurredAt, previous, requestedAt, deadline, reason);
        return ignored -> Optional.of(mutation);
    }

    private UUID activeAccount(Instant lastSuccessfulAuthAt) {
        UUID accountId = accountWithoutSecurityState(lastSuccessfulAuthAt);
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        return accountId;
    }

    private UUID accountWithoutSecurityState(Instant lastSuccessfulAuthAt) {
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                insert into identity.accounts
                    (id, lifecycle_status, lifecycle_version, last_successful_auth_at)
                values (?, cast('ACTIVE' as identity.account_lifecycle_status), 1, ?)
                """, accountId, utc(lastSuccessfulAuthAt));
        return accountId;
    }

    private UUID openSession(UUID accountId) {
        short providerId = (short) Math.floorMod(accountId.hashCode(), 30_000);
        UUID loginId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                insert into identity.auth_providers
                    (id, code, display_name, provider_type)
                values (?, ?, 'Test password', cast('PASSWORD' as identity.auth_provider_type))
                """, providerId, "TEST_" + accountId.toString().substring(0, 8));
        jdbc.update("""
                insert into identity.login_identities
                    (id, account_id, provider_id, status, activated_at, last_authenticated_at)
                values (?, ?, ?, cast('ACTIVE' as identity.login_identity_status), ?, ?)
                """, loginId, accountId, providerId, utc(NOW.minusSeconds(600)), utc(NOW.minusSeconds(60)));
        jdbc.update("""
                insert into identity.refresh_token_families
                    (id, account_id, authenticated_by_login_identity_id, auth_epoch_at_issue,
                     current_token_digest, digest_key_version, issued_at, last_rotated_at, expires_at)
                values (?, ?, ?, 1, ?, 1, ?, ?, ?)
                """, sessionId, accountId, loginId, "token:" + sessionId,
                utc(NOW), utc(NOW), utc(NOW.plusSeconds(3600)));
        return sessionId;
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AccountLifecycleJpaCommandAdapter.class, AccountLifecycleJooqQueryAdapter.class})
    static class TestApplication {}
}
