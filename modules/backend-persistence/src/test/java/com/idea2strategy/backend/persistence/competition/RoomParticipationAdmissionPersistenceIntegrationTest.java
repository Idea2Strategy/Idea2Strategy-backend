package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.competition.RoomBotProvisioningAction;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionFailure;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionContext;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionOutcome;
import com.idea2strategy.backend.application.identity.AccountLifecycleAuthenticationMethod;
import com.idea2strategy.backend.application.identity.AccountLifecycleAuthenticationProof;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommand;
import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import com.idea2strategy.backend.persistence.identity.AccountLifecycleJpaCommandAdapter;
import java.time.Clock;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionRequest;
import com.idea2strategy.backend.application.competition.RoomSubmissionTiming;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = RoomParticipationAdmissionPersistenceIntegrationTest.TestApplication.class)
class RoomParticipationAdmissionPersistenceIntegrationTest {
    private static final UUID CREATOR_ID = UUID.fromString("76000000-0000-4000-8000-000000000001");
    private static final UUID OWNER_A = UUID.fromString("76000000-0000-4000-8000-000000000002");
    private static final UUID OWNER_B = UUID.fromString("76000000-0000-4000-8000-000000000003");
    private static final UUID ROOM_A = UUID.fromString("76000000-0000-4000-8000-000000000004");
    private static final UUID ROOM_B = UUID.fromString("76000000-0000-4000-8000-000000000005");
    private static final UUID SCORING_ID = UUID.fromString("76000000-0000-4000-8000-000000000006");
    private static final UUID FEE_ID = UUID.fromString("76000000-0000-4000-8000-000000000007");
    private static final UUID BUFFER_ID = UUID.fromString("76000000-0000-4000-8000-000000000008");
    private static final UUID OPERATOR_ID = UUID.fromString("76000000-0000-4000-8000-000000000009");
    private static final UUID OUT_OF_SCOPE_INSTRUMENT = UUID.fromString("76000000-0000-4000-8000-000000000010");
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

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
    private RoomParticipationAdmissionJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountLifecycleJpaCommandAdapter lifecycleCommands;

    @BeforeEach
    void prepareReferences() {
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from competition.room_invitations");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.live_room_rules");
        jdbc.update("delete from competition.room_rules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from market_data.instruments where id = ?", OUT_OF_SCOPE_INSTRUMENT);
        jdbc.update("delete from operations.operator_accounts where id = ?", OPERATOR_ID);
        jdbc.update("delete from competition.scoring_template_versions where id = ?", SCORING_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_ID);
        jdbc.update("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.account_security_states where account_id in (?, ?, ?)", CREATOR_ID, OWNER_A, OWNER_B);
        jdbc.update("delete from identity.accounts where id in (?, ?, ?)", CREATOR_ID, OWNER_A, OWNER_B);
        var at = NOW.atOffset(ZoneOffset.UTC);
        for (UUID accountId : List.of(CREATOR_ID, OWNER_A, OWNER_B)) {
            jdbc.update(
                    "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                    accountId,
                    at);
            jdbc.update(
                    "insert into identity.account_security_states (account_id, auth_epoch, updated_at) values (?, 1, ?)",
                    accountId,
                    at);
        }
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'TOTAL_RETURN', 'v1', '{}'::jsonb, 'scoring-v1', ?)",
                SCORING_ID,
                at);
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', 'v1', 20, 'v1', 'fee-v1', ?, ?)",
                FEE_ID,
                at.minusDays(1),
                at.minusDays(1));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', 'v1', 0, 'v1', 'buffer-v1', ?, ?)",
                BUFFER_ID,
                at.minusDays(1),
                at.minusDays(1));
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, status, created_at) values (?, 'ACTIVE', ?)",
                OPERATOR_ID,
                at.minusDays(2));
    }

    @Test
    void recordsParticipationAndInitialEventInTheProvisioningTransaction() {
        insertRoom(ROOM_A, 4, 2);
        UUID botId = id(100);

        var outcome = adapter.admit(request(1, ROOM_A, OWNER_A, "bot-orchid-01"), provision(botId, OWNER_A));

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.admission().botId()).isEqualTo(botId);
        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class,
                        id(1)))
                .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject(
                        "select event_type from competition.participation_events where participation_id = ?",
                        String.class,
                        id(1)))
                .isEqualTo("PARTICIPATION_REGISTERED");
    }

    @Test
    void rejectsSecretRoomAdmissionWhenTheAccountHasNoConsumedInvitation() {
        insertRoom(ROOM_A, 4, 2);
        jdbc.update("update competition.rooms set access_type = 'SECRET' where id = ?", ROOM_A);
        var provisionCalls = new AtomicInteger();

        var outcome = adapter.admit(request(41, ROOM_A, OWNER_A, "uninvited"), context -> {
            provisionCalls.incrementAndGet();
            return id(141);
        });

        assertThat(outcome.failure()).isEqualTo(RoomParticipationAdmissionFailure.ROOM_NOT_JOINABLE);
        assertThat(provisionCalls).hasValue(0);
    }

    @Test
    void admitsOnlyTheAccountBoundToAConsumedSecretRoomInvitation() {
        insertRoom(ROOM_A, 4, 2);
        jdbc.update("update competition.rooms set access_type = 'SECRET' where id = ?", ROOM_A);
        UUID invitationId = id(742);
        jdbc.update(
                "insert into competition.room_invitations "
                        + "(id, room_id, issued_by_account_id, credential_type, credential_digest, issued_at, "
                        + "expires_at, claimed_by_account_id, claimed_at) "
                        + "values (?, ?, ?, 'CODE', 'bound-secret-digest', ?, ?, ?, ?)",
                invitationId,
                ROOM_A,
                CREATOR_ID,
                NOW.minusSeconds(60).atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC),
                OWNER_A,
                NOW.minusSeconds(30).atOffset(ZoneOffset.UTC));

        var outsider = adapter.admit(request(42, ROOM_A, OWNER_B, "outsider"), provision(id(142), OWNER_B));
        var invited = adapter.admit(request(43, ROOM_A, OWNER_A, "invited"), provision(id(143), OWNER_A));

        assertThat(outsider.failure()).isEqualTo(RoomParticipationAdmissionFailure.ROOM_NOT_JOINABLE);
        assertThat(invited.accepted()).isTrue();
        assertThat(jdbc.queryForObject(
                        "select admitted_participation_id from competition.room_invitations where id = ?",
                        UUID.class,
                        invitationId))
                .isEqualTo(id(43));
    }

    @Test
    void startsBacktestSubmissionImmediatelyDuringEvaluation() {
        insertBacktestRoom(ROOM_A, 4, 2);
        UUID botId = id(106);
        var observedContext = new AtomicReference<RoomParticipationAdmissionContext>();

        var outcome = adapter.admit(request(6, ROOM_A, OWNER_A, "bot-backtest"), context -> {
            observedContext.set(context);
            insertBot(botId, OWNER_A, context.executionEligibleFrom());
            return botId;
        });

        assertThat(outcome.accepted()).isTrue();
        assertThat(observedContext.get().submissionTiming()).isEqualTo(RoomSubmissionTiming.START_IMMEDIATELY);
        assertThat(observedContext.get().executionEligibleFrom()).isEqualTo(NOW);
    }

    @Test
    void rejectsRoomPerAccountAndExecutionCapacityBeforeProvisioning() {
        insertRoom(ROOM_A, 1, 1);
        assertThat(adapter.admit(request(1, ROOM_A, OWNER_A, "bot-a"), provision(id(101), OWNER_A)).accepted())
                .isTrue();
        var roomFull = adapter.admit(request(2, ROOM_A, OWNER_B, "bot-b"), provision(id(102), OWNER_B));
        assertThat(roomFull.failure()).isEqualTo(RoomParticipationAdmissionFailure.ROOM_CAPACITY_REACHED);

        insertRoom(ROOM_B, 2, 1);
        assertThat(adapter.admit(request(3, ROOM_B, OWNER_A, "bot-c"), provision(id(103), OWNER_A)).accepted())
                .isTrue();
        var accountRoomFull = adapter.admit(
                request(4, ROOM_B, OWNER_A, "bot-d"), provision(id(104), OWNER_A));
        assertThat(accountRoomFull.failure())
                .isEqualTo(RoomParticipationAdmissionFailure.ACCOUNT_ROOM_LIMIT_REACHED);

        for (int index = 0; index < 10; index++) {
            insertBot(id(200 + index), OWNER_B, NOW);
        }
        var provisionCalls = new AtomicInteger();
        var executionFull = adapter.admit(request(5, ROOM_B, OWNER_B, "bot-e"), context -> {
            provisionCalls.incrementAndGet();
            insertBot(id(105), OWNER_B, context.executionEligibleFrom());
            return id(105);
        });
        assertThat(executionFull.failure())
                .isEqualTo(RoomParticipationAdmissionFailure.ACCOUNT_EXECUTION_LIMIT_REACHED);
        assertThat(provisionCalls).hasValue(0);
    }

    @Test
    void concurrentRequestsCannotOverbookOneRoom() throws Exception {
        insertRoom(ROOM_A, 1, 1);
        var outcomes = runConcurrently(
                () -> adapter.admit(request(11, ROOM_A, OWNER_A, "bot-a"), provision(id(111), OWNER_A)),
                () -> adapter.admit(request(12, ROOM_A, OWNER_B, "bot-b"), provision(id(112), OWNER_B)));

        assertThat(outcomes).filteredOn(RoomParticipationAdmissionOutcome::accepted).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.accepted())
                .extracting(RoomParticipationAdmissionOutcome::failure)
                .containsExactly(RoomParticipationAdmissionFailure.ROOM_CAPACITY_REACHED);
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.participations where room_id = ?", Integer.class, ROOM_A))
                .isEqualTo(1);
    }

    @Test
    void concurrentRequestsAcrossRoomsCannotOverbookTheAccountExecutionLimit() throws Exception {
        insertRoom(ROOM_A, 2, 2);
        insertRoom(ROOM_B, 2, 2);
        for (int index = 0; index < 9; index++) {
            insertBot(id(300 + index), OWNER_A, NOW);
        }

        var outcomes = runConcurrently(
                () -> adapter.admit(request(21, ROOM_A, OWNER_A, "bot-a"), provision(id(121), OWNER_A)),
                () -> adapter.admit(request(22, ROOM_B, OWNER_A, "bot-b"), provision(id(122), OWNER_A)));

        assertThat(outcomes).filteredOn(RoomParticipationAdmissionOutcome::accepted).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.accepted())
                .extracting(RoomParticipationAdmissionOutcome::failure)
                .containsExactly(RoomParticipationAdmissionFailure.ACCOUNT_EXECUTION_LIMIT_REACHED);
    }

    @Test
    void rejectsIneligibleAccountClosedScheduleAndInvalidProvisioningWithoutPartialRows() {
        insertRoom(ROOM_A, 2, 2);
        var lifecycle = new AccountLifecycleService(
                lifecycleCommands, (candidateCutoff, limit) -> List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
        var proof = new AccountLifecycleAuthenticationProof(
                AccountLifecycleAuthenticationMethod.PASSWORD,
                OWNER_A,
                null,
                null,
                NOW,
                NOW,
                true);
        lifecycle.requestWithdrawal(new AccountLifecycleCommand(
                OWNER_A, "participation-ineligible", "participation-ineligible", UUID.randomUUID(), proof));
        var inactive = adapter.admit(request(31, ROOM_A, OWNER_A, "bot-a"), provision(id(131), OWNER_A));
        assertThat(inactive.failure()).isEqualTo(RoomParticipationAdmissionFailure.ACCOUNT_INELIGIBLE);

        lifecycle.cancelWithdrawal(new AccountLifecycleCommand(
                OWNER_A, "participation-reactivate", "participation-reactivate", UUID.randomUUID(), proof));
        jdbc.update(
                "update competition.room_schedules set participation_closes_at = ? where room_id = ?",
                NOW.atOffset(ZoneOffset.UTC),
                ROOM_A);
        var closed = adapter.admit(request(32, ROOM_A, OWNER_A, "bot-b"), provision(id(132), OWNER_A));
        assertThat(closed.failure()).isEqualTo(RoomParticipationAdmissionFailure.ROOM_NOT_JOINABLE);

        jdbc.update(
                "update competition.room_schedules set participation_closes_at = ? where room_id = ?",
                NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC),
                ROOM_A);
        UUID invalidBotId = id(133);
        var invalidBot = adapter.admit(request(33, ROOM_A, OWNER_A, "bot-c"), context -> {
            insertBot(invalidBotId, OWNER_A, context.executionEligibleFrom().plusSeconds(1));
            return invalidBotId;
        });
        assertThat(invalidBot.failure()).isEqualTo(RoomParticipationAdmissionFailure.PROVISIONED_BOT_INVALID);
        assertThat(jdbc.queryForObject(
                        "select count(*) from bot.bots where id = ?", Integer.class, invalidBotId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.participations where id in (?, ?, ?)",
                        Integer.class,
                        id(31),
                        id(32),
                        id(33)))
                .isZero();
    }

    @Test
    void enforcesLockedAccountAgeAndStoppedBotSlotPoliciesBeforeProvisioning() {
        insertRoom(ROOM_A, 1, 1);
        jdbc.update("update identity.accounts set created_at = ? where id = ?", NOW.minus(java.time.Duration.ofDays(2)).atOffset(ZoneOffset.UTC), OWNER_A);
        jdbc.update("update competition.room_rules set eligibility_document = '{\"minimumAccountAgeDays\":30}'::jsonb where room_id = ?", ROOM_A);
        var calls = new AtomicInteger();

        var tooNew = adapter.admit(request(61, ROOM_A, OWNER_A, "too-new"), context -> {
            calls.incrementAndGet();
            return id(161);
        });
        assertThat(tooNew.failure()).isEqualTo(RoomParticipationAdmissionFailure.ACCOUNT_INELIGIBLE);
        assertThat(calls).hasValue(0);

        jdbc.update("update competition.room_rules set eligibility_document = '{}'::jsonb where room_id = ?", ROOM_A);
        UUID stoppedBot = id(162);
        insertBot(stoppedBot, OWNER_B, NOW);
        jdbc.update("insert into competition.participations (id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, withdrawn_at) values (?, ?, ?, ?, 'stopped', 'WITHDRAWN', ?, ?)", id(62), ROOM_A, stoppedBot, OWNER_B, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
        var retained = adapter.admit(request(63, ROOM_A, OWNER_A, "retained-slot"), provision(id(163), OWNER_A));
        assertThat(retained.failure()).isEqualTo(RoomParticipationAdmissionFailure.ROOM_CAPACITY_REACHED);

        jdbc.update("update competition.live_room_rules set stopped_bot_slot_policy = 'RELEASE_SLOT' where room_id = ?", ROOM_A);
        assertThat(adapter.admit(request(64, ROOM_A, OWNER_A, "released-slot"), provision(id(164), OWNER_A)).accepted()).isTrue();
    }

    @Test
    void rollsBackAProvisionedBotWhoseImmutableSnapshotIsOutsideTheRoomMarketScope() {
        insertRoom(ROOM_A, 2, 2);
        jdbc.update("update competition.room_rules set market_scope_document = '{\"market\":\"US\"}'::jsonb where room_id = ?", ROOM_A);
        jdbc.update("insert into market_data.instruments (id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XETR', 'EUR')", OUT_OF_SCOPE_INSTRUMENT);
        UUID botId = id(165);

        var outcome = adapter.admit(request(65, ROOM_A, OWNER_A, "wrong-market"), context -> {
            insertBot(botId, OWNER_A, context.executionEligibleFrom());
            jdbc.update("insert into bot.launch_snapshots (bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, presentation_hash, snapshot_hash, created_at) values (?, 'v1', ?::jsonb, '{}'::jsonb, 'a', 'b', 'c', ?)", botId, "{\"groups\":[{\"instrumentIds\":[\"" + OUT_OF_SCOPE_INSTRUMENT + "\"]}]}", NOW.atOffset(ZoneOffset.UTC));
            return botId;
        });

        assertThat(outcome.failure()).isEqualTo(RoomParticipationAdmissionFailure.MARKET_SCOPE_MISMATCH);
        assertThat(jdbc.queryForObject("select count(*) from bot.bots where id = ?", Integer.class, botId)).isZero();
    }

    private List<RoomParticipationAdmissionOutcome> runConcurrently(
            java.util.concurrent.Callable<RoomParticipationAdmissionOutcome> first,
            java.util.concurrent.Callable<RoomParticipationAdmissionOutcome> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RoomParticipationAdmissionOutcome> firstResult = executor.submit(() -> {
                start.await();
                return first.call();
            });
            Future<RoomParticipationAdmissionOutcome> secondResult = executor.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
        }
    }

    private RoomParticipationAdmissionRequest request(
            int suffix, UUID roomId, UUID ownerId, String alias) {
        return new RoomParticipationAdmissionRequest(
                id(suffix), id(500 + suffix), roomId, ownerId, alias, NOW);
    }

    private RoomBotProvisioningAction provision(UUID botId, UUID ownerId) {
        return context -> {
            insertBot(botId, ownerId, context.executionEligibleFrom());
            return botId;
        };
    }

    private void insertBot(UUID botId, UUID ownerId, Instant eligibleFrom) {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', 'Prepared bot', 'RUNNING', ?, ?, ?, ?)",
                botId,
                ownerId,
                at,
                eligibleFrom.atOffset(ZoneOffset.UTC),
                at,
                at);
    }

    private void insertRoom(UUID roomId, int roomLimit, int accountLimit) {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'Room', 'PUBLIC', 'RECRUITING', ?)",
                roomId,
                CREATOR_ID,
                at.minusDays(2));
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, bot_participation_limit, "
                        + "per_account_bot_limit, eligibility_document, market_scope_document, scoring_parameters, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, precision_rules_version, "
                        + "rules_hash, locked_at) values (?, ?, 100000, ?, ?, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, "
                        + "?, 5, ?, 'v1', 'rules-v1', ?)",
                roomId,
                SCORING_ID,
                roomLimit,
                accountLimit,
                FEE_ID,
                BUFFER_ID,
                at.minusDays(2));
        jdbc.update(
                "insert into competition.live_room_rules "
                        + "(room_id, stopped_bot_slot_policy, minimum_operation_seconds, minimum_fill_count) "
                        + "values (?, 'COUNT_UNTIL_END', 3600, 5)",
                roomId);
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                roomId,
                at.minusHours(1),
                at.minusMinutes(30),
                at.plusHours(2),
                at.plusHours(1),
                at.plusHours(3),
                at.plusHours(4));
    }

    private void insertBacktestRoom(UUID roomId, int roomLimit, int accountLimit) {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, created_by_operator_id, name, access_type, status, created_at) "
                        + "values (?, 'BACKTEST', 'PLATFORM', ?, 'Backtest Room', 'PUBLIC', 'EVALUATING', ?)",
                roomId,
                OPERATOR_ID,
                at.minusDays(2));
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, bot_participation_limit, "
                        + "per_account_bot_limit, eligibility_document, market_scope_document, scoring_parameters, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, precision_rules_version, "
                        + "rules_hash, locked_at) values (?, ?, 100000, ?, ?, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, "
                        + "?, 5, ?, 'v1', 'rules-v1', ?)",
                roomId,
                SCORING_ID,
                roomLimit,
                accountLimit,
                FEE_ID,
                BUFFER_ID,
                at.minusDays(2));
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                roomId,
                at.minusHours(2),
                at.minusHours(2),
                at.minusHours(1),
                at.plusHours(1),
                at.plusHours(2),
                at.plusHours(3));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("77000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({RoomParticipationAdmissionJooqAdapter.class, AccountLifecycleJpaCommandAdapter.class})
    static class TestApplication {}
}
