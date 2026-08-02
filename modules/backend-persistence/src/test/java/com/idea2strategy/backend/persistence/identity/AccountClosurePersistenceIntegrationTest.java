package com.idea2strategy.backend.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.accountclosure.AccountClosureCandidate;
import com.idea2strategy.backend.application.accountclosure.AccountClosureCoordinator;
import com.idea2strategy.backend.application.accountclosure.AccountClosureReadinessProbe;
import com.idea2strategy.backend.application.accountclosure.AccountClosureRunResult;
import com.idea2strategy.backend.application.accountclosure.AccountClosureStore;
import com.idea2strategy.backend.application.accountclosure.ClosureDomain;
import com.idea2strategy.backend.application.accountclosure.ClosureReadiness;
import com.idea2strategy.backend.application.accountclosure.ClosureReadinessStatus;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommandType;
import com.idea2strategy.backend.application.identity.AccountLifecycleMutation;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
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
    @Autowired DSLContext dsl;
    @Autowired BotStopCommandJooqAdapter botStops;

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
                new ClosureReadiness(ClosureDomain.BOT, ClosureReadinessStatus.BLOCKED,
                        "NEWER_BLOCKER", "{}", DEADLINE.plusSeconds(2)));
        assertThatThrownBy(() -> closure.recordReadiness(accountId, correlationId, generation,
                new ClosureReadiness(ClosureDomain.BOT, ClosureReadinessStatus.FROZEN,
                        "LATE_OLDER_RESULT", "{}", DEADLINE.plusSeconds(1))))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("Stale account closure readiness generation or observation");
        assertThat(jdbc.queryForObject("""
                select cast(status as text) from identity.account_closure_readiness
                where correlation_id = ? and generation = ? and domain = 'BOT'
                """, String.class, correlationId, generation)).isEqualTo("BLOCKED");
        closure.recordReadiness(accountId, correlationId, generation,
                new ClosureReadiness(ClosureDomain.BOT, ClosureReadinessStatus.FROZEN,
                        "LATEST_READY", "{}", DEADLINE.plusSeconds(3)));

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
    void concurrentPostgresCoordinatorsCommitOneClosedHeadReceiptAndOutbox() throws Exception {
        UUID accountId = UUID.randomUUID();
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", accountId);
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        lifecycle.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "concurrent-request", "concurrent-request-hash", UUID.randomUUID(), ignored -> Optional.of(
                        new AccountLifecycleMutation(AccountLifecycleStatus.CLOSING, REQUESTED,
                                AccountLifecycleStatus.ACTIVE, REQUESTED, DEADLINE, "WITHDRAWAL_REQUESTED")));

        var beginBarrier = new CyclicBarrier(2);
        AccountClosureStore racingStore = new AccountClosureStore() {
            @Override public List<AccountClosureCandidate> findClosingCandidates(int limit) {
                return closure.findClosingCandidates(limit);
            }
            @Override public long beginAttempt(
                    AccountClosureCandidate candidate, UUID correlationId, Instant startedAt) {
                long generation = closure.beginAttempt(candidate, correlationId, startedAt);
                try {
                    beginBarrier.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return generation;
            }
            @Override public void recordReadiness(
                    UUID id, UUID correlationId, long generation, ClosureReadiness readiness) {
                closure.recordReadiness(id, correlationId, generation, readiness);
            }
            @Override public boolean closeIfReady(
                    AccountClosureCandidate candidate, UUID correlationId, long generation,
                    String idempotencyKey, Instant closedAt) {
                return closure.closeIfReady(candidate, correlationId, generation, idempotencyKey, closedAt);
            }
        };
        List<AccountClosureReadinessProbe> probes = Arrays.stream(ClosureDomain.values())
                .<AccountClosureReadinessProbe>map(domain -> new AccountClosureReadinessProbe() {
                    @Override public ClosureDomain domain() { return domain; }
                    @Override public ClosureReadiness evaluate(UUID id, UUID correlationId, Instant observedAt) {
                        return new ClosureReadiness(domain,
                                domain == ClosureDomain.TRADING
                                        ? ClosureReadinessStatus.SETTLED
                                        : ClosureReadinessStatus.FROZEN,
                                "READY", "{}", observedAt);
                    }
                }).toList();
        var coordinator = new AccountClosureCoordinator(
                racingStore, probes, closure, Clock.fixed(DEADLINE, ZoneOffset.UTC), Duration.ofHours(1));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<AccountClosureRunResult>>of(
                    () -> coordinator.run(1), () -> coordinator.run(1)), 30, TimeUnit.SECONDS);
            assertThat(results).allMatch(result -> !result.isCancelled());
            assertThat(results.get(0).get().closed() + results.get(1).get().closed()).isOne();
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account_lifecycle_events
                where account_id = ? and command_type = 'ACCOUNT_CLOSED'
                """, Integer.class, accountId)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account_lifecycle_command_receipts
                where account_id = ? and command_type = 'ACCOUNT_CLOSED'
                """, Integer.class, accountId)).isOne();
        assertThat(jdbc.queryForObject("""
                select count(*) from operations.outbox_messages
                where aggregate_id = ? and event_type = 'ACCOUNT_ACCESS_REVOKED'
                  and payload_document ->> 'cause' = 'ACCOUNT_CLOSED'
                """, Integer.class, accountId)).isOne();
    }

    @Test
    void concreteBotAndTradingProbesRequireStoppedBotsAndZeroTradingAssets() {
        UUID accountId = activeAccount();
        UUID botId = insertBot(accountId, "RUNNING");
        jdbc.update("""
                insert into bot.launch_snapshots
                    (bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot,
                     semantic_hash, presentation_hash, snapshot_hash)
                values (?, 'v1', '{}'::jsonb, '{}'::jsonb, 'semantic', 'presentation', 'snapshot')
                """, botId);
        jdbc.update("""
                insert into trading.bot_budget_projections
                    (bot_id, currency_code, available_cash_amount, active_reservation_amount,
                     invested_amount, segregated_short_proceeds_amount, short_collateral_amount,
                     valuation_at, valuation_status, last_event_sequence, projection_hash, updated_at)
                values (?, 'USD', 1, 0, 0, 0, 0, ?, 'CURRENT', 1, 'budget', ?)
                """, botId, REQUESTED.atOffset(ZoneOffset.UTC), REQUESTED.atOffset(ZoneOffset.UTC));

        var botProbe = new BotAccountClosureReadinessProbe(dsl, botStops);
        assertThat(botProbe.evaluate(accountId, UUID.randomUUID(), REQUESTED).status())
                .isEqualTo(ClosureReadinessStatus.FREEZE_REQUESTED);
        assertThat(jdbc.queryForObject(
                "select cast(lifecycle_status as text) from bot.bots where id = ?", String.class, botId))
                .isEqualTo("STOPPING");
        jdbc.update("update bot.bots set lifecycle_status = 'STOPPED', stopped_at = ? where id = ?",
                REQUESTED.atOffset(ZoneOffset.UTC), botId);
        assertThat(botProbe.evaluate(accountId, UUID.randomUUID(), REQUESTED.plusSeconds(1)).status())
                .isEqualTo(ClosureReadinessStatus.FROZEN);

        var tradingProbe = new TradingAccountClosureReadinessProbe(dsl);
        assertThat(tradingProbe.evaluate(accountId, UUID.randomUUID(), REQUESTED).status())
                .isEqualTo(ClosureReadinessStatus.SETTLEMENT_REQUIRED);
        jdbc.update("""
                update trading.bot_budget_projections
                set available_cash_amount = 0, active_reservation_amount = 0, invested_amount = 0,
                    segregated_short_proceeds_amount = 0, short_collateral_amount = 0
                where bot_id = ?
                """, botId);
        assertThat(tradingProbe.evaluate(accountId, UUID.randomUUID(), REQUESTED.plusSeconds(1)).status())
                .isEqualTo(ClosureReadinessStatus.SETTLED);
    }

    @Test
    void concreteCompetitionProbeWithdrawsRegisteredAndBlocksEvaluating() {
        UUID accountId = activeAccount();
        UUID registeredBot = insertBot(accountId, "STOPPED");
        UUID roomId = UUID.randomUUID();
        jdbc.update("""
                insert into competition.rooms
                    (id, competition_type, organizer_type, creator_account_id, name, access_type, status)
                values (?, 'LIVE_PAPER', 'USER', ?, 'closure-room', 'PUBLIC', 'RECRUITING')
                """, roomId, accountId);
        UUID registered = UUID.randomUUID();
        jdbc.update("""
                insert into competition.participations
                    (id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at)
                values (?, ?, ?, ?, 'registered', 'REGISTERED', ?)
                """, registered, roomId, registeredBot, accountId, REQUESTED.atOffset(ZoneOffset.UTC));
        var probe = new CompetitionAccountClosureReadinessProbe(dsl);

        assertThat(probe.evaluate(accountId, UUID.randomUUID(), REQUESTED).status())
                .isEqualTo(ClosureReadinessStatus.FROZEN);
        assertThat(jdbc.queryForObject(
                "select cast(status as text) from competition.participations where id = ?",
                String.class, registered)).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("""
                select count(*) from competition.participation_events
                where participation_id = ? and event_type = 'PARTICIPATION_WITHDRAWN'
                  and reason_code = 'ACCOUNT_CLOSING'
                """, Integer.class, registered)).isOne();

        UUID evaluatingBot = insertBot(accountId, "STOPPED");
        UUID evaluating = UUID.randomUUID();
        jdbc.update("""
                insert into competition.participations
                    (id, room_id, bot_id, owner_account_id, anonymous_alias, status,
                     joined_at, evaluation_started_at)
                values (?, ?, ?, ?, 'evaluating', 'EVALUATING', ?, ?)
                """, evaluating, roomId, evaluatingBot, accountId,
                REQUESTED.atOffset(ZoneOffset.UTC), REQUESTED.atOffset(ZoneOffset.UTC));
        assertThat(probe.evaluate(accountId, UUID.randomUUID(), REQUESTED.plusSeconds(1)).status())
                .isEqualTo(ClosureReadinessStatus.BLOCKED);
        assertThat(jdbc.queryForObject(
                "select cast(status as text) from competition.participations where id = ?",
                String.class, evaluating)).isEqualTo("EVALUATING");
        jdbc.update("""
                update competition.participations
                set status = 'WITHDRAWN', withdrawn_at = ?
                where id = ?
                """, REQUESTED.atOffset(ZoneOffset.UTC), evaluating);
        UUID activeBot = insertBot(accountId, "STOPPED");
        UUID active = UUID.randomUUID();
        jdbc.update("""
                insert into competition.participations
                    (id, room_id, bot_id, owner_account_id, anonymous_alias, status,
                     joined_at, evaluation_started_at)
                values (?, ?, ?, ?, 'active', 'ACTIVE', ?, ?)
                """, active, roomId, activeBot, accountId,
                REQUESTED.atOffset(ZoneOffset.UTC), REQUESTED.atOffset(ZoneOffset.UTC));
        assertThat(probe.evaluate(accountId, UUID.randomUUID(), REQUESTED.plusSeconds(2)).status())
                .isEqualTo(ClosureReadinessStatus.BLOCKED);
        assertThat(jdbc.queryForObject(
                "select cast(status as text) from competition.participations where id = ?",
                String.class, active)).isEqualTo("ACTIVE");
    }

    @Test
    void concreteNotificationAndIntegrationProbesFreezeAccountActivity() {
        UUID accountId = activeAccount();
        jdbc.update("""
                insert into operations.notification_preferences
                    (account_id, event_type, channel, enabled, updated_at)
                values (?, 'TEST', 'EMAIL', true, ?)
                """, accountId, REQUESTED.atOffset(ZoneOffset.UTC));
        jdbc.update("""
                insert into operations.account_integrations (account_id, integration_code, status)
                values (?, 'TEST', 'ACTIVE')
                """, accountId);
        lifecycle.executeAtomically(accountId, AccountLifecycleCommandType.REQUEST_WITHDRAWAL,
                "probe-request", "probe-request-hash", UUID.randomUUID(), ignored -> Optional.of(
                        new AccountLifecycleMutation(AccountLifecycleStatus.CLOSING, REQUESTED,
                                AccountLifecycleStatus.ACTIVE, REQUESTED, DEADLINE, "WITHDRAWAL_REQUESTED")));

        var notification = new NotificationAccountClosureReadinessProbe(dsl);
        assertThat(notification.evaluate(accountId, UUID.randomUUID(), REQUESTED).status())
                .isEqualTo(ClosureReadinessStatus.FROZEN);
        assertThat(jdbc.queryForObject("""
                select enabled from operations.notification_preferences
                where account_id = ? and event_type = 'TEST' and channel = 'EMAIL'
                """, Boolean.class, accountId)).isFalse();

        var integration = new IntegrationAccountClosureReadinessProbe(dsl);
        assertThat(integration.evaluate(accountId, UUID.randomUUID(), REQUESTED).status())
                .isEqualTo(ClosureReadinessStatus.FREEZE_REQUESTED);
        assertThat(jdbc.queryForObject("""
                select status from operations.account_integrations
                where account_id = ? and integration_code = 'TEST'
                """, String.class, accountId)).isEqualTo("CLOSING");
        jdbc.update("""
                update operations.account_integrations
                set status = 'CLOSED', closed_at = ?, updated_at = ?
                where account_id = ? and integration_code = 'TEST'
                """, REQUESTED.atOffset(ZoneOffset.UTC), REQUESTED.atOffset(ZoneOffset.UTC), accountId);
        assertThat(integration.evaluate(accountId, UUID.randomUUID(), REQUESTED.plusSeconds(1)).status())
                .isEqualTo(ClosureReadinessStatus.FROZEN);
    }

    private UUID activeAccount() {
        UUID accountId = UUID.randomUUID();
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", accountId);
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        return accountId;
    }

    private UUID insertBot(UUID accountId, String status) {
        UUID botId = UUID.randomUUID();
        jdbc.update("""
                insert into bot.bots
                    (id, owner_account_id, mode, name, lifecycle_status,
                     lifecycle_changed_at, execution_eligible_from)
                values (?, ?, 'BASIC', ?, cast(? as bot.lifecycle_status), ?, ?)
                """, botId, accountId, "bot-" + botId, status,
                REQUESTED.atOffset(ZoneOffset.UTC), REQUESTED.atOffset(ZoneOffset.UTC));
        return botId;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void closingAccountCannotCreateNewNotificationOrIntegrationActivity() {
        UUID accountId = UUID.randomUUID();
        UUID existingBotId = UUID.randomUUID();
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", accountId);
        jdbc.update("insert into identity.account_security_states (account_id) values (?)", accountId);
        jdbc.update("""
                insert into bot.bots
                    (id, owner_account_id, mode, name, lifecycle_status,
                     lifecycle_changed_at, execution_eligible_from)
                values (?, ?, 'BASIC', 'existing', 'STOPPED', ?, ?)
                """, existingBotId, accountId,
                REQUESTED.atOffset(ZoneOffset.UTC), REQUESTED.atOffset(ZoneOffset.UTC));
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
        assertThatThrownBy(() -> jdbc.update("""
                insert into bot.bots
                    (id, owner_account_id, mode, name, lifecycle_status,
                     lifecycle_changed_at, execution_eligible_from)
                values (?, ?, 'BASIC', 'blocked', 'STOPPED', ?, ?)
                """, UUID.randomUUID(), accountId,
                REQUESTED.atOffset(ZoneOffset.UTC), REQUESTED.atOffset(ZoneOffset.UTC)))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("account is not ACTIVE");
        assertThatThrownBy(() -> jdbc.update("""
                insert into competition.participations
                    (id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at)
                values (?, ?, ?, ?, 'blocked', 'REGISTERED', ?)
                """, UUID.randomUUID(), UUID.randomUUID(), existingBotId, accountId,
                REQUESTED.atOffset(ZoneOffset.UTC)))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("account is not ACTIVE");
        UUID otherActiveAccount = activeAccount();
        assertThatThrownBy(() -> jdbc.update("""
                insert into competition.participations
                    (id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at)
                values (?, ?, ?, ?, 'mismatch', 'REGISTERED', ?)
                """, UUID.randomUUID(), UUID.randomUUID(), existingBotId, otherActiveAccount,
                REQUESTED.atOffset(ZoneOffset.UTC)))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("participation owner does not match bot owner");
        assertThatThrownBy(() -> jdbc.update("""
                insert into trading.orders
                    (id, bot_id, partition_id, instrument_id, order_key, side, order_type,
                     time_in_force, requested_quantity, broker_rules_version,
                     precision_rules_version, slippage_rate_bps, fee_policy_id,
                     accepted_event_id, accepted_at, contract_hash)
                values (?, ?, ?, ?, 'blocked', 'BUY', 'MARKET', 'DAY', 1,
                        'v1', 'v1', 5, ?, ?, ?, 'hash')
                """, UUID.randomUUID(), existingBotId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), REQUESTED.atOffset(ZoneOffset.UTC)))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("account is not ACTIVE");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AccountLifecycleJpaCommandAdapter.class,
            AccountClosureJpaStore.class,
            BotStopCommandJooqAdapter.class
    })
    static class TestApplication {}
}
