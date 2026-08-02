package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.ParticipationExitAction;
import com.idea2strategy.backend.application.competition.OperatorRoomPermissions;
import com.idea2strategy.backend.application.competition.RoomTerminationConflictException;
import com.idea2strategy.backend.persistence.botcontrol.BotRunCommandJooqAdapter;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = RoomTerminationPersistenceIntegrationTest.TestApplication.class)
class RoomTerminationPersistenceIntegrationTest {
    private static final UUID OWNER_ID = id(1);
    private static final UUID ROOM_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID PARTICIPATION_ID = id(4);
    private static final UUID OPERATOR_ID = id(5);
    private static final UUID PARTICIPANT_OWNER_ID = id(8);
    private static final UUID ROLE_ID = id(20);
    private static final UUID READ_PERMISSION_ID =
            UUID.fromString("e3000000-0000-4000-8000-000000000001");
    private static final UUID MANAGE_PERMISSION_ID =
            UUID.fromString("e3000000-0000-4000-8000-000000000002");
    private static final UUID SCORING_ID = id(23);
    private static final UUID FEE_POLICY_ID = id(24);
    private static final UUID BUFFER_POLICY_ID = id(25);
    private static final UUID FINAL_SNAPSHOT_ID = id(26);
    private static final UUID PERFORMANCE_SNAPSHOT_ID = id(27);
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

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

    @Autowired RoomTerminationJooqAdapter adapter;
    @Autowired OperatorRoomJooqAdapter operatorRoomAdapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from operations.audit_events where actor_id = ?", OPERATOR_ID);
        jdbc.update("delete from operations.outbox_messages");
        jdbc.update("delete from bot.continuation_deadlines");
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from competition.leaderboard_entries");
        jdbc.update("delete from competition.leaderboard_snapshots");
        jdbc.update("delete from performance.bot_snapshots");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.room_events");
        jdbc.update("delete from competition.live_room_rules");
        jdbc.update("delete from competition.room_rules");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.launch_snapshots");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from competition.scoring_template_versions where id = ?", SCORING_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_POLICY_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_POLICY_ID);
        jdbc.update("delete from operations.operator_role_assignments where role_id = ?", ROLE_ID);
        jdbc.update("delete from operations.operator_accounts where id = ?", OPERATOR_ID);
        jdbc.update("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbc.update("delete from identity.accounts where id = ?", PARTICIPANT_OWNER_ID);
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE'), (?, 'ACTIVE')",
                OWNER_ID, PARTICIPANT_OWNER_ID);
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, external_identity_key_hmac, status, mfa_enrolled_at, created_at) "
                        + "values (?, 'operator-e12', 'ACTIVE', ?, ?)",
                OPERATOR_ID, utc(NOW.minusSeconds(60)), utc(NOW.minusSeconds(60)));
        jdbc.update(
                "insert into operations.roles (id, code, hierarchy_rank, status) "
                        + "values (?, 'E30_OPERATOR', 10, 'ACTIVE') on conflict (id) do nothing",
                ROLE_ID);
        jdbc.update(
                "insert into operations.permissions (id, code, description, sensitivity) values "
                        + "(?, ?, 'Read official competition rooms', 'SENSITIVE'), "
                        + "(?, ?, 'Manage official competition rooms', 'HIGH') on conflict (id) do nothing",
                READ_PERMISSION_ID, OperatorRoomPermissions.READ,
                MANAGE_PERMISSION_ID, OperatorRoomPermissions.MANAGE);
        jdbc.update(
                "insert into operations.role_permissions (role_id, permission_id) values (?, ?), (?, ?) "
                        + "on conflict (role_id, permission_id) do nothing",
                ROLE_ID, READ_PERMISSION_ID, ROLE_ID, MANAGE_PERMISSION_ID);
        jdbc.update("""
                insert into operations.rbac_catalog_versions
                    (catalog_version, content_hash, status)
                values ('e30-room-test-v1', ?, 'DRAFT')
                on conflict (catalog_version) do nothing
                """, "e".repeat(64));
        jdbc.update("""
                insert into operations.rbac_catalog_roles
                    (catalog_version, role_id, hierarchy_rank, role_status)
                select 'e30-room-test-v1', ?, 10, 'ACTIVE'
                where not exists (
                    select 1 from operations.rbac_catalog_roles
                    where catalog_version = 'e30-room-test-v1' and role_id = ?)
                """, ROLE_ID, ROLE_ID);
        jdbc.update("""
                insert into operations.rbac_catalog_permissions
                    (catalog_version, permission_id, permission_status)
                select 'e30-room-test-v1', id, 'ACTIVE'
                from operations.permissions
                where id in (?, ?)
                  and not exists (
                    select 1 from operations.rbac_catalog_permissions snapshot
                    where snapshot.catalog_version = 'e30-room-test-v1'
                      and snapshot.permission_id = operations.permissions.id)
                """, READ_PERMISSION_ID, MANAGE_PERMISSION_ID);
        jdbc.update("""
                insert into operations.rbac_catalog_role_permissions
                    (catalog_version, role_id, permission_id, delegable)
                select 'e30-room-test-v1', ?, id, false
                from operations.permissions
                where id in (?, ?)
                  and not exists (
                    select 1 from operations.rbac_catalog_role_permissions snapshot
                    where snapshot.catalog_version = 'e30-room-test-v1'
                      and snapshot.role_id = ?
                      and snapshot.permission_id = operations.permissions.id)
                """, ROLE_ID, READ_PERMISSION_ID, MANAGE_PERMISSION_ID, ROLE_ID);
        jdbc.update("""
                update operations.rbac_catalog_versions
                set status = 'ACTIVE', activated_at = clock_timestamp()
                where catalog_version = 'e30-room-test-v1' and status = 'DRAFT'
                """);
        jdbc.update(
                "insert into operations.operator_role_assignments "
                        + "(id, operator_account_id, role_id, catalog_version, granted_by_operator_id, granted_at) "
                        + "values (?, ?, ?, 'e30-room-test-v1', ?, ?)",
                id(28), OPERATOR_ID, ROLE_ID, OPERATOR_ID, utc(NOW.minusSeconds(60)));
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'TOTAL_RETURN', 'e30', '{}'::jsonb, 'e30-scoring', ?)",
                SCORING_ID, utc(NOW.minusSeconds(7200)));
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, "
                        + "rules_hash, effective_from, published_at) "
                        + "values (?, 'E30', 'v1', 20, 'v1', 'e30-fee', ?, ?)",
                FEE_POLICY_ID, utc(NOW.minusSeconds(7200)), utc(NOW.minusSeconds(7200)));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, "
                        + "rules_hash, effective_from, published_at) "
                        + "values (?, 'E30', 'v1', 0, 'v1', 'e30-buffer', ?, ?)",
                BUFFER_POLICY_ID, utc(NOW.minusSeconds(7200)), utc(NOW.minusSeconds(7200)));
    }

    @Test
    void withdrawsAWaitingBotIntoImmediatePrivateExecution() {
        seedRoom("RECRUITING", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "REGISTERED");

        adapter.withdrawOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.CONTINUE_PRIVATE,
                "USER_REQUESTED", NOW);

        assertThat(value("select status::text from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo("WITHDRAWN");
        assertThat(instant("select execution_eligible_from from bot.bots where id = ?", BOT_ID)).isEqualTo(NOW);
        assertThat(instant("select due_at from bot.continuation_deadlines where bot_id = ?", BOT_ID))
                .isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_RUN_COMMAND'"))
                .isEqualTo(1);
        assertThatThrownBy(() -> adapter.withdrawOwned(
                        ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.CONTINUE_PRIVATE,
                        "USER_REQUESTED", NOW.plusSeconds(1)))
                .isInstanceOf(RoomTerminationConflictException.class);
        assertThat(count("select count(*) from operations.outbox_messages")).isEqualTo(1);
    }

    @Test
    void withdrawsAnEvaluatingBotAndRequestsSettlement() {
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");

        adapter.withdrawOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.STOP,
                "OWNER_STOPPED", NOW);

        assertThat(value("select lifecycle_status::text from bot.bots where id = ?", BOT_ID))
                .isEqualTo("STOPPING");
        assertThat(value("select stop_reason_code from bot.bots where id = ?", BOT_ID))
                .isEqualTo("ROOM_WITHDRAWAL");
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_STOP_COMMAND'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from bot.continuation_deadlines")).isZero();
    }

    @Test
    void acceptsTheMaximumAuditReasonWithoutOverflowingTheBotStopCode() {
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");
        String reason = "R".repeat(80);

        adapter.withdrawOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.STOP, reason, NOW);

        assertThat(value("select stop_reason_code from bot.bots where id = ?", BOT_ID))
                .isEqualTo("ROOM_WITHDRAWAL");
        assertThat(value("select withdrawal_reason_code from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo(reason);
    }

    @Test
    void creatorCancellationIsAllowedOnlyBeforeSubmissionOpens() {
        seedRoom("RECRUITING", NOW.plusSeconds(60), NOW.plusSeconds(3600));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "REGISTERED");

        assertThat(adapter.cancelOwned(ROOM_ID, OWNER_ID, "CREATOR_REQUESTED", NOW)
                        .participationsTerminated())
                .isEqualTo(1);
        assertThat(value("select status::text from competition.rooms where id = ?", ROOM_ID))
                .isEqualTo("CANCELLED");
        assertThat(value("select event_type from competition.room_events where room_id = ?", ROOM_ID))
                .isEqualTo("ROOM_CANCELLED");
    }

    @Test
    void creatorCancellationAfterSubmissionLeavesTheRoomUntouched() {
        seedRoom("RECRUITING", NOW.minusSeconds(1), NOW.plusSeconds(3600));

        assertThatThrownBy(() -> adapter.cancelOwned(ROOM_ID, OWNER_ID, "TOO_LATE", NOW))
                .isInstanceOf(RoomTerminationConflictException.class);
        assertThat(value("select status::text from competition.rooms where id = ?", ROOM_ID))
                .isEqualTo("RECRUITING");
        assertThat(count("select count(*) from competition.room_events")).isZero();
    }

    @Test
    void platformInvalidationDetachesWaitingAndEvaluatingBotsWithAuditEvidence() {
        UUID secondBot = id(6);
        UUID secondParticipation = id(7);
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");
        seedParticipation(secondBot, secondParticipation, "REGISTERED");

        assertThat(adapter.invalidate(ROOM_ID, OPERATOR_ID, "OFFICIAL_LEDGER_INTEGRITY", NOW)
                        .participationsTerminated())
                .isEqualTo(2);

        assertThat(value("select status::text from competition.rooms where id = ?", ROOM_ID))
                .isEqualTo("INVALIDATED");
        assertThat(value("select invalidation_reason_code from competition.rooms where id = ?", ROOM_ID))
                .isEqualTo("OFFICIAL_LEDGER_INTEGRITY");
        assertThat(value("select status::text from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo("EVALUATION_FAILED");
        assertThat(value("select status::text from competition.participations where id = ?", secondParticipation))
                .isEqualTo("WITHDRAWN");
        assertThat(count("select count(*) from bot.continuation_deadlines")).isEqualTo(2);
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_RUN_COMMAND'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from competition.participation_events "
                + "where event_type = 'ROOM_INVALIDATED'"))
                .isEqualTo(2);
    }

    @Test
    void platformInvalidationPreservesAnAlreadyStoppingBot() {
        UUID secondBot = id(6);
        UUID secondParticipation = id(7);
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");
        seedParticipation(secondBot, secondParticipation, "REGISTERED");
        jdbc.update(
                "update bot.bots set lifecycle_status = 'STOPPING'::bot.lifecycle_status, "
                        + "stop_requested_at = ?, stop_reason_code = 'OWNER_STOPPED' where id = ?",
                utc(NOW.minusSeconds(10)), BOT_ID);

        assertThat(adapter.invalidate(ROOM_ID, OPERATOR_ID, "SYSTEM_SAFETY", NOW)
                        .participationsTerminated())
                .isEqualTo(2);
        assertThat(value("select lifecycle_status::text from bot.bots where id = ?", BOT_ID))
                .isEqualTo("STOPPING");
        assertThat(count("select count(*) from bot.continuation_deadlines where bot_id = '" + BOT_ID + "'"))
                .isZero();
    }

    @Test
    void authorizesOnlyEffectiveRolePermissionsAndAuditsAllowedAndDeniedAttempts() {
        assertThat(operatorRoomAdapter.authorize(
                OPERATOR_ID, OperatorRoomPermissions.READ, "COMPETITION_ROOM_VIEW", ROOM_ID, NOW))
                .isTrue();
        assertThat(operatorRoomAdapter.authorize(
                OPERATOR_ID, "NOT_GRANTED", "COMPETITION_ROOM_VIEW", ROOM_ID, NOW))
                .isFalse();
        assertThat(count("select count(*) from operations.audit_events where actor_id = '" + OPERATOR_ID + "'"))
                .isEqualTo(2);
        assertThat(jdbc.queryForList(
                        "select reason_code from operations.audit_events where actor_id = ? order by recorded_at",
                        String.class, OPERATOR_ID))
                .containsExactlyInAnyOrder("PERMISSION_GRANTED", "PERMISSION_DENIED");
    }

    @Test
    void operatorCancelsOnlyAnOfficialRoomBeforeSubmissionOpens() {
        seedRoom("RECRUITING", NOW.plusSeconds(60), NOW.plusSeconds(3600));
        makeOfficial();

        adapter.cancelOfficial(ROOM_ID, OPERATOR_ID, "OPERATOR_CANCELLED", NOW);

        assertThat(value("select status::text from competition.rooms where id = ?", ROOM_ID))
                .isEqualTo("CANCELLED");
        assertThat(value("select event_type from competition.room_events where room_id = ?", ROOM_ID))
                .isEqualTo("ROOM_CANCELLED");
    }

    @Test
    void readsOnlySafeOfficialRoomEventsAndFinalProvenance() {
        seedRoom("ENDED", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        makeOfficial();
        jdbc.update("update competition.rooms set ended_at = ? where id = ?", utc(NOW), ROOM_ID);
        seedRoomRules();
        seedParticipation(BOT_ID, PARTICIPATION_ID, "REGISTERED");
        jdbc.update(
                "update competition.participations set anonymous_alias = 'safe-alias' where id = ?",
                PARTICIPATION_ID);
        jdbc.update(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, reason_code, occurred_at, "
                        + "payload_document) values (?, ?, 1, 'PARTICIPATION_ADMITTED', null, ?, "
                        + "'{\"privateAccountId\":\"hidden\"}'::jsonb)",
                id(29), PARTICIPATION_ID, utc(NOW.minusSeconds(900)));
        jdbc.update(
                "insert into performance.bot_snapshots "
                        + "(id, bot_id, snapshot_type, source_event_sequence, evaluated_at, equity_amount, "
                        + "total_return_pct, max_drawdown_pct, sharpe_ratio, metrics_document, input_hash, "
                        + "calculation_rules_version, snapshot_hash, created_at) "
                        + "values (?, ?, 'LEADERBOARD_CUTOFF', 10, ?, 110000, 10, 2, 1.5, "
                        + "'{\"privateMetric\":true}'::jsonb, 'input', 'v1', 'snapshot', ?)",
                PERFORMANCE_SNAPSHOT_ID, BOT_ID, utc(NOW), utc(NOW));
        jdbc.update(
                "insert into competition.leaderboard_snapshots "
                        + "(id, room_id, scoring_template_version_id, cutoff_at, status, result_hash, created_at) "
                        + "values (?, ?, ?, ?, 'FINAL', 'final-hash', ?)",
                FINAL_SNAPSHOT_ID, ROOM_ID, SCORING_ID, utc(NOW), utc(NOW));
        String provenance = "sha256:" + "a".repeat(64);
        jdbc.update(
                "insert into competition.leaderboard_entries "
                        + "(snapshot_id, participation_id, performance_snapshot_id, rank, is_joint_rank, "
                        + "eligibility_status, score, tie_break_document, calculation_document) "
                        + "values (?, ?, ?, 1, false, 'ELIGIBLE', 10, '{\"privateTie\":true}'::jsonb, "
                        + "jsonb_build_object('provenanceHash', ?, 'privateCalculation', true))",
                FINAL_SNAPSHOT_ID, PARTICIPATION_ID, PERFORMANCE_SNAPSHOT_ID, provenance);

        var view = operatorRoomAdapter.findOfficialRoom(ROOM_ID).orElseThrow();

        assertThat(view.room().roomId()).isEqualTo(ROOM_ID);
        assertThat(view.participationEvents()).singleElement()
                .satisfies(event -> assertThat(event.anonymousAlias()).isEqualTo("safe-alias"));
        assertThat(view.finalResult().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.provenanceHash()).isEqualTo(provenance));
        assertThat(view.toString())
                .doesNotContain(OWNER_ID.toString(), BOT_ID.toString(), PARTICIPATION_ID.toString())
                .doesNotContain("privateAccountId", "privateMetric", "privateTie", "privateCalculation");
    }

    @Test
    void withdrawalSerializesBehindTheRoomEndTransition() throws Exception {
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");
        var roomLocked = new CountDownLatch(1);
        var releaseRoom = new CountDownLatch(1);
        var transactions = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var ending = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                jdbc.queryForObject("select id from competition.rooms where id = ? for update", UUID.class, ROOM_ID);
                jdbc.update("update competition.rooms set status = 'ENDED'::competition.room_status where id = ?", ROOM_ID);
                roomLocked.countDown();
                try {
                    releaseRoom.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(roomLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var withdrawal = executor.submit(() -> adapter.withdrawOwned(
                    ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.CONTINUE_PRIVATE,
                    "TOO_LATE", NOW));

            assertThat(withdrawal.isDone()).isFalse();
            releaseRoom.countDown();
            ending.get();
            assertThatThrownBy(withdrawal::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(com.idea2strategy.backend.application.competition.RoomTerminationAccessException.class);
        } finally {
            releaseRoom.countDown();
        }
        assertThat(value("select status::text from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo("EVALUATING");
    }

    @Test
    void secretRoomCreatorExpelsAWaitingBotWithoutRecordingAReason() {
        seedRoom("RECRUITING", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        jdbc.update("update competition.rooms set access_type = 'SECRET'::competition.room_access_type where id = ?", ROOM_ID);
        seedParticipation(BOT_ID, PARTICIPATION_ID, PARTICIPANT_OWNER_ID, "REGISTERED");

        assertThat(adapter.expelOwned(ROOM_ID, PARTICIPATION_ID, OWNER_ID, NOW)
                        .participationsTerminated())
                .isEqualTo(1);

        assertThat(value("select status::text from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo("EXPELLED");
        assertThat(jdbc.queryForObject(
                        "select expulsion_reason_code is null from competition.participations where id = ?",
                        Boolean.class, PARTICIPATION_ID))
                .isTrue();
        assertThat(instant("select execution_eligible_from from bot.bots where id = ?", BOT_ID)).isEqualTo(NOW);
        assertThat(instant("select due_at from bot.continuation_deadlines where bot_id = ?", BOT_ID))
                .isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_RUN_COMMAND'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from operations.outbox_messages "
                + "where event_type = 'ROOM_PARTICIPATION_EXPELLED_NOTIFICATION'"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select not (payload_document ? 'reasonCode') from operations.outbox_messages "
                                + "where event_type = 'ROOM_PARTICIPATION_EXPELLED_NOTIFICATION'",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select reason_code is null from competition.participation_events "
                                + "where participation_id = ? and event_type = 'PARTICIPATION_EXPELLED'",
                        Boolean.class, PARTICIPATION_ID))
                .isTrue();
    }

    @Test
    void publicRoomCreatorCannotExpel() {
        seedRoom("RECRUITING", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "REGISTERED");

        assertThatThrownBy(() -> adapter.expelOwned(ROOM_ID, PARTICIPATION_ID, OWNER_ID, NOW))
                .isInstanceOf(RoomTerminationConflictException.class)
                .hasMessageContaining("secret room");
        assertThat(value("select status::text from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
    }

    @Test
    void secretRoomCreatorCannotExpelTheirOwnParticipation() {
        seedRoom("RECRUITING", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        jdbc.update("update competition.rooms set access_type = 'SECRET'::competition.room_access_type where id = ?", ROOM_ID);
        seedParticipation(BOT_ID, PARTICIPATION_ID, "REGISTERED");

        assertThatThrownBy(() -> adapter.expelOwned(ROOM_ID, PARTICIPATION_ID, OWNER_ID, NOW))
                .isInstanceOf(RoomTerminationConflictException.class)
                .hasMessageContaining("own participation");
        assertThat(value("select status::text from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
        assertThat(count("select count(*) from competition.participation_events")).isZero();
        assertThat(count("select count(*) from operations.outbox_messages")).isZero();
    }

    @Test
    void expulsionPreservesAnAlreadyStoppingBot() {
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        jdbc.update("update competition.rooms set access_type = 'SECRET'::competition.room_access_type where id = ?", ROOM_ID);
        seedParticipation(BOT_ID, PARTICIPATION_ID, PARTICIPANT_OWNER_ID, "EVALUATING");
        jdbc.update(
                "update bot.bots set lifecycle_status = 'STOPPING'::bot.lifecycle_status, "
                        + "stop_requested_at = ?, stop_reason_code = 'OWNER_STOPPED' where id = ?",
                utc(NOW.minusSeconds(10)), BOT_ID);

        adapter.expelOwned(ROOM_ID, PARTICIPATION_ID, OWNER_ID, NOW);

        assertThat(value("select lifecycle_status::text from bot.bots where id = ?", BOT_ID))
                .isEqualTo("STOPPING");
        assertThat(count("select count(*) from bot.continuation_deadlines")).isZero();
    }

    private void seedRoom(String status, Instant participationOpensAt, Instant evaluationStartsAt) {
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'E12 Room', 'PUBLIC', "
                        + "?::competition.room_status, ?)",
                ROOM_ID, OWNER_ID, status, utc(NOW.minusSeconds(7200)));
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                ROOM_ID, utc(NOW.minusSeconds(7200)), utc(participationOpensAt), utc(evaluationStartsAt),
                utc(NOW.plusSeconds(7200)), utc(NOW.plusSeconds(10800)), utc(NOW.plusSeconds(14400)));
    }

    private void seedParticipation(UUID botId, UUID participationId, String status) {
        seedParticipation(botId, participationId, OWNER_ID, status);
    }

    private void seedParticipation(UUID botId, UUID participationId, UUID ownerAccountId, String status) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, 0, ?)",
                botId, ownerAccountId, "E12 Bot " + botId, utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(3600)),
                utc(NOW.plusSeconds(3600)), utc(NOW.minusSeconds(3600)));
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                botId, "semantic-" + botId, "presentation-" + botId, "snapshot-" + botId,
                utc(NOW.minusSeconds(3600)));
        boolean evaluating = "EVALUATING".equals(status);
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, evaluation_started_at) "
                        + "values (?, ?, ?, ?, ?, ?::competition.participation_status, ?, ?)",
                participationId, ROOM_ID, botId, ownerAccountId, "alias-" + participationId, status,
                utc(NOW.minusSeconds(1800)), evaluating ? utc(NOW.minusSeconds(900)) : null);
    }

    private void makeOfficial() {
        jdbc.update(
                "update competition.rooms set organizer_type = 'PLATFORM', creator_account_id = null, "
                        + "created_by_operator_id = ? where id = ?",
                OPERATOR_ID, ROOM_ID);
    }

    private void seedRoomRules() {
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, currency_code, "
                        + "bot_participation_limit, per_account_bot_limit, eligibility_document, "
                        + "market_scope_document, scoring_parameters, fee_policy_id, slippage_rate_bps, "
                        + "buying_power_buffer_policy_id, precision_rules_version, rules_hash, locked_at) "
                        + "values (?, ?, 100000, 'USD', 10, 3, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, "
                        + "?, 5, ?, '1.0', 'e30-room-rules', ?)",
                ROOM_ID, SCORING_ID, FEE_POLICY_ID, BUFFER_POLICY_ID, utc(NOW.minusSeconds(7200)));
        jdbc.update(
                "insert into competition.live_room_rules "
                        + "(room_id, stopped_bot_slot_policy, minimum_operation_seconds, minimum_fill_count) "
                        + "values (?, 'COUNT_UNTIL_END', 0, 0)",
                ROOM_ID);
    }

    private String value(String sql, Object argument) {
        return jdbc.queryForObject(sql, String.class, argument);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private Instant instant(String sql, Object argument) {
        return jdbc.queryForObject(sql, OffsetDateTime.class, argument).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("88000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        RoomTerminationJooqAdapter.class,
        OperatorRoomJooqAdapter.class,
        BotRunCommandJooqAdapter.class,
        BotStopCommandJooqAdapter.class
    })
    static class TestApplication {}
}
