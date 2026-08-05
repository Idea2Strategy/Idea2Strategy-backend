package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.idea2strategy.backend.application.competition.RoomEvaluationStartReport;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import com.idea2strategy.backend.persistence.backtest.FeatureMaterializationPinResolver;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
@SpringBootTest(classes = RoomEvaluationStartPersistenceIntegrationTest.TestApplication.class)
class RoomEvaluationStartPersistenceIntegrationTest {
    private static final UUID OWNER_ID = id(1);
    private static final UUID ROOM_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID PARTICIPATION_ID = id(4);
    private static final UUID SCORING_ID = id(5);
    private static final UUID FEE_ID = id(6);
    private static final UUID BUFFER_ID = id(7);
    private static final UUID OPERATOR_ID = id(8);
    private static final UUID SECOND_BOT_ID = id(9);
    private static final UUID SECOND_PARTICIPATION_ID = id(10);
    private static final UUID PROVIDER_ID = id(11);
    private static final UUID FEED_ID = id(12);
    private static final UUID FIRST_DATASET_ID = id(13);
    private static final UUID SECOND_DATASET_ID = id(14);
    private static final UUID FIRST_PERIOD_ID = id(15);
    private static final UUID SECOND_PERIOD_ID = id(16);
    private static final UUID FEATURE_INSTRUMENT_ID = id(17);
    private static final UUID FEATURE_PIPELINE_RUN_ID = id(18);
    private static final UUID FEATURE_MATERIALIZATION_ID = id(19);
    private static final UUID FEATURE_MANIFEST_ID = id(20);
    private static final UUID FEATURE_OBJECT_ID = id(21);
    private static final UUID FEATURE_DATASET_OBJECT_ID = id(22);
    private static final Instant EVALUATION_START = Instant.parse("2026-08-02T04:00:00Z");
    private static final Instant OBSERVED_AT = EVALUATION_START.plusSeconds(15);

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
    private RoomEvaluationStartJooqAdapter adapter;

    @Autowired
    private RoomEvaluationAccountResultConsumer results;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void prepareReferences() {
        jdbc.update("delete from operations.outbox_consumer_receipts");
        jdbc.update("delete from competition.room_evaluation_account_results");
        jdbc.update("delete from operations.outbox_messages");
        jdbc.update("delete from trading.ledger_entries");
        jdbc.update("delete from trading.ledger_transactions");
        jdbc.update("delete from trading.ledger_accounts");
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from competition.live_evaluation_segments");
        jdbc.update("delete from competition.backtest_period_runs");
        jdbc.update("delete from backtest.run_attempts");
        jdbc.update("delete from backtest.input_datasets");
        jdbc.update("delete from backtest.input_feature_materializations");
        jdbc.update("delete from backtest.run_input_pins");
        jdbc.update("delete from backtest.input_bundles");
        jdbc.update("delete from backtest.runs");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.backtest_period_feature_materializations");
        jdbc.update("delete from competition.backtest_period_datasets");
        jdbc.update("delete from competition.backtest_evaluation_periods");
        jdbc.update("delete from competition.backtest_evaluation_plans");
        jdbc.update("delete from backtest.execution_policy_versions where version = 'competition-policy-v1'");
        jdbc.update("delete from market_data.feature_materializations where id = ?", FEATURE_MATERIALIZATION_ID);
        jdbc.update("delete from market_data.dataset_objects where id = ?", FEATURE_DATASET_OBJECT_ID);
        jdbc.update("delete from storage.objects where id = ?", FEATURE_OBJECT_ID);
        jdbc.update("delete from market_data.dataset_manifests where id = ?", FEATURE_MANIFEST_ID);
        jdbc.update("delete from market_data.pipeline_runs where id = ?", FEATURE_PIPELINE_RUN_ID);
        jdbc.update("delete from bot.bot_events");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.live_room_rules");
        jdbc.update("delete from competition.room_rules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.launch_configurations");
        jdbc.update("delete from bot.launch_contract_plans");
        jdbc.update("delete from bot.launch_snapshots");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from competition.scoring_template_versions where id = ?", SCORING_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_ID);
        jdbc.update("delete from operations.operator_accounts where id = ?", OPERATOR_ID);
        jdbc.update("delete from market_data.dataset_manifests where id in (?, ?)", FIRST_DATASET_ID, SECOND_DATASET_ID);
        jdbc.update("delete from market_data.feeds where id = ?", FEED_ID);
        jdbc.update("delete from market_data.providers where id = ?", PROVIDER_ID);
        jdbc.update("delete from market_data.instruments where id = ?", FEATURE_INSTRUMENT_ID);
        jdbc.update("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);
        var at = EVALUATION_START.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, external_identity_key_hmac, external_identity_key_version, status, mfa_enrolled_at, created_at) "
                        + "values (?, 'operator-e11', 1, 'ACTIVE', ?, ?)",
                OPERATOR_ID, at.minusDays(2), at.minusDays(2));
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'TOTAL_RETURN', 'v1', '{}'::jsonb, 'scoring-e11', ?)",
                SCORING_ID, at.minusDays(2));
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', 'v1', 20, 'v1', 'fee-e11', ?, ?)",
                FEE_ID, at.minusDays(2), at.minusDays(2));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', 'v1', 0, 'v1', 'buffer-e11', ?, ?)",
                BUFFER_ID, at.minusDays(2), at.minusDays(2));
        jdbc.update(
                "insert into market_data.providers "
                        + "(id, code, display_name, rights_version, status, created_at) "
                        + "values (?, 'E11_BACKTEST', 'E11 Backtest', 'v1', 'ACTIVE', ?)",
                PROVIDER_ID, at.minusDays(2));
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'E11_BACKTEST', 'BAR', '1d', 'UTC', 'v1', ?)",
                FEED_ID, PROVIDER_ID, at.minusDays(2));
        seedDataset(FIRST_DATASET_ID, "5", at);
        seedDataset(SECOND_DATASET_ID, "6", at);
    }

    @Test
    void stagesOwnedStateAndEmitsOneContractCompatibleAccountOpenRequest() throws Exception {
        seedLiveParticipation();

        assertThat(adapter.startEligible(OBSERVED_AT, 10))
                .isEqualTo(new RoomEvaluationStartReport(OBSERVED_AT, 1));
        assertThat(adapter.startEligible(OBSERVED_AT.plusSeconds(5), 10))
                .isEqualTo(new RoomEvaluationStartReport(OBSERVED_AT.plusSeconds(5), 0));

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("PENDING_LEDGER");
        assertThat(jdbc.queryForObject(
                        "select evaluation_started_at is null from competition.participations where id = ?",
                        Boolean.class, PARTICIPATION_ID))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select started_at is null from bot.bots where id = ?",
                        Boolean.class, BOT_ID))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from trading.ledger_accounts where bot_id = ?",
                        Integer.class, BOT_ID))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from trading.ledger_transactions where bot_id = ?",
                        Integer.class, BOT_ID))
                .isZero();
        assertThat(jdbc.queryForList(
                        "select direction::text as direction, amount from trading.ledger_entries "
                                + "where bot_id = ? order by direction",
                        BOT_ID))
                .extracting(row -> row.get("direction"))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from competition.participation_events where participation_id = ?",
                        Integer.class, PARTICIPATION_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from bot.bot_events where bot_id = ?",
                        Integer.class, BOT_ID))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages "
                                + "where aggregate_id = ? and event_type = 'ROOM_EVALUATION_ACCOUNT_OPEN_REQUESTED'",
                        Integer.class, PARTICIPATION_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'effectiveAt' from operations.outbox_messages "
                                + "where aggregate_id = ? and event_type = 'ROOM_EVALUATION_ACCOUNT_OPEN_REQUESTED'",
                        String.class, PARTICIPATION_ID))
                .isEqualTo(EVALUATION_START.toString());

        UUID expectedSegmentId = UUID.nameUUIDFromBytes(
                ("live-evaluation-segment.v1:" + PARTICIPATION_ID).getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForMap(
                        "select id, segment_type, starts_at, ends_at, start_event_sequence, initial_state_hash "
                                + "from competition.live_evaluation_segments where participation_id = ?",
                        PARTICIPATION_ID))
                .satisfies(segment -> {
                    assertThat(segment.get("id")).isEqualTo(expectedSegmentId);
                    assertThat(segment.get("segment_type")).isEqualTo("OFFICIAL_EVALUATION");
                    assertThat(((java.sql.Timestamp) segment.get("starts_at")).toInstant())
                            .isEqualTo(EVALUATION_START);
                    assertThat(((java.sql.Timestamp) segment.get("ends_at")).toInstant())
                            .isEqualTo(EVALUATION_START.plusSeconds(2 * 60 * 60));
                    assertThat(segment.get("start_event_sequence")).isNull();
                    assertThat(segment.get("initial_state_hash")).isNull();
                });
        String payload = jdbc.queryForObject(
                "select payload_document::text from operations.outbox_messages "
                        + "where aggregate_id = ? and event_type = 'ROOM_EVALUATION_ACCOUNT_OPEN_REQUESTED'",
                String.class, PARTICIPATION_ID);
        var command = objectMapper.readTree(payload);
        UUID expectedCommandId = UUID.nameUUIDFromBytes(
                ("room-evaluation-account-open-command.v1:" + PARTICIPATION_ID)
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(command.path("commandId").asText()).isEqualTo(expectedCommandId.toString());
        assertThat(command.path("roomId").asText()).isEqualTo(ROOM_ID.toString());
        assertThat(command.path("participationId").asText()).isEqualTo(PARTICIPATION_ID.toString());
        assertThat(command.path("botId").asText()).isEqualTo(BOT_ID.toString());
        assertThat(command.path("evaluationSegmentId").asText()).isEqualTo(expectedSegmentId.toString());
        assertThat(command.path("initialCash").asText()).isEqualTo("100000.00000000");
        assertThat(command.path("currency").asText()).isEqualTo("USD");
        assertThat(command.path("effectiveAt").asText()).isEqualTo(EVALUATION_START.toString());
        assertThat(jdbc.queryForMap(
                        "select event_schema_version, owner_domain from operations.outbox_messages "
                                + "where aggregate_id = ?",
                        PARTICIPATION_ID))
                .containsEntry("event_schema_version", "room-evaluation-account-open-requested.v1")
                .containsEntry("owner_domain", "room-performance");
    }

    @Test
    void concurrentWorkersStartOneParticipationOnce() throws Exception {
        seedLiveParticipation();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RoomEvaluationStartReport> first = executor.submit(() -> {
                gate.await();
                return adapter.startEligible(OBSERVED_AT, 10);
            });
            Future<RoomEvaluationStartReport> second = executor.submit(() -> {
                gate.await();
                return adapter.startEligible(OBSERVED_AT, 10);
            });
            gate.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .extracting(RoomEvaluationStartReport::participantsStarted)
                    .containsExactlyInAnyOrder(1, 0);
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.live_evaluation_segments where participation_id = ?",
                        Integer.class, PARTICIPATION_ID))
                .isEqualTo(1);
    }

    @Test
    void recordsOneCommonLiveInputHashForEveryBotAndBindsItIntoBotSpecificInitialState() {
        seedLiveParticipation();
        seedBotAndParticipation(
                SECOND_BOT_ID, SECOND_PARTICIPATION_ID,
                EVALUATION_START.atOffset(ZoneOffset.UTC));

        assertThat(adapter.startEligible(OBSERVED_AT, 10).participantsStarted()).isEqualTo(2);

        assertThat(jdbc.queryForList(
                        "select payload_document ->> 'liveEvaluationInputVersion' as version, "
                                + "payload_document ->> 'liveEvaluationInputHash' as input_hash "
                                + "from competition.participation_events "
                                + "where event_type = 'LEDGER_PENDING' order by participation_id"))
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.get("version")).isEqualTo("live-evaluation-input.v1");
                    assertThat(event.get("input_hash")).asString().matches("sha256:[0-9a-f]{64}");
                })
                .extracting(event -> event.get("input_hash"))
                .containsOnly(jdbc.queryForObject(
                        "select payload_document ->> 'liveEvaluationInputHash' "
                                + "from competition.participation_events where participation_id = ?",
                        String.class, PARTICIPATION_ID));
        assertThat(jdbc.queryForList(
                "select initial_state_hash from competition.live_evaluation_segments order by participation_id",
                        String.class))
                .hasSize(2)
                .allSatisfy(value -> assertThat(value).isNull());
    }

    @Test
    void rejectsAChangedLockedInputBeforeStartingAnotherBotAndRollsBackThatAttempt() {
        seedLiveParticipation();

        assertThat(adapter.startEligible(OBSERVED_AT, 1).participantsStarted()).isEqualTo(1);
        String lockedHash = jdbc.queryForObject(
                "select payload_document ->> 'liveEvaluationInputHash' "
                        + "from competition.participation_events where participation_id = ?",
                String.class, PARTICIPATION_ID);
        seedBotAndParticipation(
                SECOND_BOT_ID, SECOND_PARTICIPATION_ID,
                EVALUATION_START.atOffset(ZoneOffset.UTC));
        jdbc.update(
                "update competition.room_rules set scoring_parameters = '{\"weight\":2}'::jsonb, "
                        + "rules_hash = 'rules-e17-changed' where room_id = ?",
                ROOM_ID);

        assertThatThrownBy(() -> adapter.startEligible(OBSERVED_AT.plusSeconds(1), 10))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("live evaluation input does not match");

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, SECOND_PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.live_evaluation_segments where participation_id = ?",
                        Integer.class, SECOND_PARTICIPATION_ID))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'liveEvaluationInputHash' "
                                + "from competition.participation_events where participation_id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo(lockedHash);
    }

    @Test
    void startsLateBacktestSubmissionFromItsActualAdmissionTime() throws Exception {
        Instant admittedAt = EVALUATION_START.plusSeconds(5);
        seedBacktestParticipation(admittedAt);

        assertThat(adapter.startEligible(OBSERVED_AT, 10).participantsStarted()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'effectiveAt' from operations.outbox_messages "
                                + "where aggregate_id = ? and event_type = 'ROOM_EVALUATION_ACCOUNT_OPEN_REQUESTED'",
                        String.class, PARTICIPATION_ID))
                .isEqualTo(admittedAt.toString());
        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("PENDING_LEDGER");
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.live_evaluation_segments where participation_id = ?",
                        Integer.class, PARTICIPATION_ID))
                .isZero();
        consumeOpenedLedgerResult();
        assertThat(jdbc.queryForMap(
                        "select event_schema_version, payload_document ->> 'requestReason' as request_reason, "
                                + "payload_document ->> 'roomId' as room_id, "
                                + "payload_document ->> 'participationId' as participation_id, "
                                + "payload_document ->> 'scoringTemplateVersionId' as scoring_template_id, "
                                + "payload_document ->> 'roomRulesHash' as room_rules_hash, "
                                + "payload_document ->> 'initialCashAmount' as initial_cash_amount, "
                                + "payload_document ->> 'currencyCode' as currency_code, "
                                + "jsonb_array_length(payload_document -> 'periods') as period_count, "
                                + "payload_document #>> '{periods,0,evaluationPeriodId}' as first_period_id, "
                                + "payload_document #>> '{periods,0,datasets,0,datasetManifestId}' as first_dataset_id "
                                + "from operations.outbox_messages "
                                + "where event_type = 'COMPETITION_BACKTEST_REQUESTED' "
                                + "and payload_document ->> 'participationId' = ? "
                                + "and payload_document #>> '{periods,0,evaluationPeriodId}' = ?",
                        PARTICIPATION_ID.toString(), FIRST_PERIOD_ID.toString()))
                .containsEntry("event_schema_version", "backtest-request.v1")
                .containsEntry("request_reason", "COMPETITION_EVALUATION")
                .containsEntry("room_id", ROOM_ID.toString())
                .containsEntry("participation_id", PARTICIPATION_ID.toString())
                .containsEntry("scoring_template_id", SCORING_ID.toString())
                .containsEntry("room_rules_hash", "sha256:" + "7".repeat(64))
                .containsEntry("initial_cash_amount", "100000.00000000")
                .containsEntry("currency_code", "USD")
                .containsEntry("period_count", 1)
                .containsEntry("first_period_id", FIRST_PERIOD_ID.toString())
                .containsEntry("first_dataset_id", FIRST_DATASET_ID.toString());
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages "
                                + "where event_type = 'COMPETITION_BACKTEST_REQUESTED'",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from backtest.runs", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from backtest.run_input_pins", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from backtest.input_bundles", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from backtest.input_datasets", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from backtest.input_feature_materializations",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from backtest.run_input_pins p "
                                + "join backtest.input_bundles b on b.id = p.input_bundle_id "
                                + "where p.input_contract_version = 'backtest-request.v1' "
                                + "and p.input_bundle_fingerprint = b.bundle_hash",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.backtest_period_runs where participation_id = ?",
                        Integer.class, PARTICIPATION_ID))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select coalesce(bool_or(jsonb_exists(payload_document, 'liveEvaluationInputHash')), false) "
                                + "from competition.participation_events where participation_id = ?",
                        Boolean.class, PARTICIPATION_ID))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "select coalesce(bool_or(jsonb_exists(payload_document, 'periods')), false) "
                                + "from competition.participation_events where participation_id = ?",
                        Boolean.class, PARTICIPATION_ID))
                .isFalse();
    }

    @Test
    void matchingOpenedFactAdvancesPendingParticipationExactlyOnce() throws Exception {
        seedLiveParticipation();
        assertThat(adapter.startEligible(OBSERVED_AT, 10).participantsStarted()).isEqualTo(1);

        var request = jdbc.queryForMap("""
                select id, payload_document::text as payload, payload_hash, producer_idempotency_key
                from operations.outbox_messages
                where aggregate_id = ? and event_type = 'ROOM_EVALUATION_ACCOUNT_OPEN_REQUESTED'
                """, PARTICIPATION_ID);
        var requestPayload = objectMapper.readTree(request.get("payload").toString());
        UUID resultId = id(81);
        var payload = objectMapper.createObjectNode()
                .put("requestMessageId", request.get("id").toString())
                .put("commandId", requestPayload.path("commandId").asText())
                .put("producerIdempotencyKey", request.get("producer_idempotency_key").toString())
                .put("requestPayloadHash", request.get("payload_hash").toString())
                .put("roomId", ROOM_ID.toString())
                .put("participationId", PARTICIPATION_ID.toString())
                .put("botId", BOT_ID.toString())
                .put("evaluationSegmentId", requestPayload.path("evaluationSegmentId").asText())
                .put("botEventId", id(82).toString())
                .put("botEventSequence", 1)
                .put("ledgerTransactionId", id(83).toString())
                .put("cashAccountId", id(84).toString())
                .put("capitalAccountId", id(85).toString())
                .put("initialCash", requestPayload.path("initialCash").asText())
                .put("currency", requestPayload.path("currency").asText())
                .put("feePolicyVersionId", requestPayload.path("feePolicyVersionId").asText())
                .put("buyingPowerPolicyVersionId", requestPayload.path("buyingPowerPolicyVersionId").asText())
                .put("completedAt", OBSERVED_AT.plusSeconds(1).toString());
        jdbc.update("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                     event_schema_version, payload_document, producer_idempotency_key,
                     idempotency_key, created_at)
                values (?, 'trading', ?, 1, 'ROOM_EVALUATION_ACCOUNT_OPENED',
                        'room-evaluation-account-opened.v1', cast(? as jsonb), ?, ?, ?)
                """, resultId, PARTICIPATION_ID, objectMapper.writeValueAsString(payload),
                request.get("producer_idempotency_key"), "opened:" + resultId,
                OBSERVED_AT.plusSeconds(1).atOffset(ZoneOffset.UTC));
        var result = jdbc.queryForMap("""
                select payload_document::text as payload, payload_hash, producer_idempotency_key
                from operations.outbox_messages where id = ?
                """, resultId);
        var source = new TransactionalOutboxStore.ClaimedMessage(
                resultId, "trading", PARTICIPATION_ID, "ROOM_EVALUATION_ACCOUNT_OPENED",
                "room-evaluation-account-opened.v1", result.get("payload").toString(),
                result.get("payload_hash").toString(), result.get("producer_idempotency_key").toString(),
                null, 1, OBSERVED_AT, OBSERVED_AT.plusSeconds(30));

        assertThat(results.consume(source, "test", Duration.ofSeconds(30)))
                .isEqualTo(RoomEvaluationAccountResultConsumer.Outcome.OPENED);
        assertThat(results.consume(source, "test", Duration.ofSeconds(30)))
                .isEqualTo(RoomEvaluationAccountResultConsumer.Outcome.DUPLICATE);
        assertThat(jdbc.queryForObject("select status::text from competition.participations where id = ?",
                String.class, PARTICIPATION_ID)).isEqualTo("EVALUATING");
        assertThat(jdbc.queryForMap("select start_event_sequence, initial_state_hash "
                + "from competition.live_evaluation_segments where participation_id = ?", PARTICIPATION_ID))
                .satisfies(row -> {
                    assertThat(row.get("start_event_sequence")).isEqualTo(1L);
                    assertThat(row.get("initial_state_hash")).asString().matches("sha256:[0-9a-f]{64}");
                });
        assertThat(jdbc.queryForObject("select count(*) from competition.room_evaluation_account_results "
                + "where participation_id = ? and applied_at is not null", Integer.class, PARTICIPATION_ID))
                .isEqualTo(1);
    }

    @Test
    void ledgerHandoffDoesNotPretendToValidateBacktestInputsBeforeAccountOpening() {
        seedBacktestParticipation(EVALUATION_START.plusSeconds(5));
        jdbc.update(
                "update market_data.dataset_manifests set dataset_hash = ? where id = ?",
                "0".repeat(64), FIRST_DATASET_ID);

        assertThat(adapter.startEligible(OBSERVED_AT, 10).participantsStarted()).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("PENDING_LEDGER");
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages where aggregate_id = ?",
                        Integer.class, PARTICIPATION_ID))
                .isEqualTo(1);
    }

    @Test
    void rejectsAnEmptyLiveEvaluationWindowWithoutCreatingOfficialState() {
        seedLiveParticipation();
        jdbc.update(
                "update competition.room_schedules set evaluation_ends_at = ?, "
                        + "finalization_deadline_at = ? where room_id = ?",
                EVALUATION_START.atOffset(ZoneOffset.UTC),
                EVALUATION_START.plusSeconds(60).atOffset(ZoneOffset.UTC),
                ROOM_ID);

        assertThatThrownBy(() -> adapter.startEligible(OBSERVED_AT, 10))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("non-empty schedule window");

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject("select count(*) from competition.live_evaluation_segments", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from bot.bot_events", Integer.class))
                .isZero();
    }

    @Test
    void rejectsNonEmptyOfficialStateWithoutPartialInitialization() {
        seedLiveParticipation();
        jdbc.update(
                "insert into trading.ledger_accounts "
                        + "(id, bot_id, account_key, account_type, currency_code, created_at) "
                        + "values (?, ?, 'dirty:cash', 'CASH', 'USD', ?)",
                id(50), BOT_ID, EVALUATION_START.atOffset(ZoneOffset.UTC));

        assertThatThrownBy(() -> adapter.startEligible(OBSERVED_AT, 10))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("official state is not empty");

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject("select count(*) from operations.outbox_messages", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from trading.ledger_transactions", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from competition.live_evaluation_segments", Integer.class))
                .isZero();
    }

    @Test
    void rollsBackLiveSegmentAndOfficialStateWhenStartCommandCannotBeRecorded() {
        seedLiveParticipation();
        UUID outboxId = UUID.nameUUIDFromBytes(
                ("room-evaluation-account-open-message.v1:" + PARTICIPATION_ID).getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                "insert into operations.outbox_messages "
                        + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, "
                        + "event_schema_version, payload_document, idempotency_key, created_at) "
                        + "values (?, 'room-performance', ?, 1, 'EXISTING', 'test.v1', '{}'::jsonb, ?, ?)",
                outboxId, id(90), "existing:" + PARTICIPATION_ID, OBSERVED_AT.atOffset(ZoneOffset.UTC));

        assertThatThrownBy(() -> adapter.startEligible(OBSERVED_AT, 10))
                .hasStackTraceContaining("duplicate key");

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject("select count(*) from competition.live_evaluation_segments", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from trading.ledger_transactions", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from bot.bot_events", Integer.class))
                .isZero();
    }

    private void seedLiveParticipation() {
        var at = EVALUATION_START.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'E11 Room', 'PUBLIC', 'EVALUATING', ?)",
                ROOM_ID, OWNER_ID, at.minusDays(1));
        seedRoomRulesAndSchedule(at);
        jdbc.update(
                "insert into competition.live_room_rules "
                        + "(room_id, stopped_bot_slot_policy, minimum_operation_seconds, minimum_fill_count) "
                        + "values (?, 'COUNT_UNTIL_END', 0, 0)",
                ROOM_ID);
        seedBotAndParticipation(at);
    }

    private void consumeOpenedLedgerResult() throws Exception {
        var request = jdbc.queryForMap("""
                select id, payload_document::text as payload, payload_hash, producer_idempotency_key
                from operations.outbox_messages
                where aggregate_id = ? and event_type = 'ROOM_EVALUATION_ACCOUNT_OPEN_REQUESTED'
                """, PARTICIPATION_ID);
        var requestPayload = objectMapper.readTree(request.get("payload").toString());
        UUID resultId = id(91);
        var payload = objectMapper.createObjectNode()
                .put("requestMessageId", request.get("id").toString())
                .put("commandId", requestPayload.path("commandId").asText())
                .put("producerIdempotencyKey", request.get("producer_idempotency_key").toString())
                .put("requestPayloadHash", request.get("payload_hash").toString())
                .put("roomId", ROOM_ID.toString())
                .put("participationId", PARTICIPATION_ID.toString())
                .put("botId", BOT_ID.toString())
                .put("evaluationSegmentId", requestPayload.path("evaluationSegmentId").asText())
                .put("botEventId", id(92).toString())
                .put("botEventSequence", 1)
                .put("ledgerTransactionId", id(93).toString())
                .put("cashAccountId", id(94).toString())
                .put("capitalAccountId", id(95).toString())
                .put("initialCash", requestPayload.path("initialCash").asText())
                .put("currency", requestPayload.path("currency").asText())
                .put("feePolicyVersionId", requestPayload.path("feePolicyVersionId").asText())
                .put("buyingPowerPolicyVersionId", requestPayload.path("buyingPowerPolicyVersionId").asText())
                .put("completedAt", OBSERVED_AT.plusSeconds(1).toString());
        jdbc.update("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                     event_schema_version, payload_document, producer_idempotency_key,
                     idempotency_key, created_at)
                values (?, 'trading', ?, 1, 'ROOM_EVALUATION_ACCOUNT_OPENED',
                        'room-evaluation-account-opened.v1', cast(? as jsonb), ?, ?, ?)
                """, resultId, PARTICIPATION_ID, objectMapper.writeValueAsString(payload),
                request.get("producer_idempotency_key"), "opened:" + resultId,
                OBSERVED_AT.plusSeconds(1).atOffset(ZoneOffset.UTC));
        var result = jdbc.queryForMap("""
                select payload_document::text as payload, payload_hash, producer_idempotency_key
                from operations.outbox_messages where id = ?
                """, resultId);
        var source = new TransactionalOutboxStore.ClaimedMessage(
                resultId, "trading", PARTICIPATION_ID, "ROOM_EVALUATION_ACCOUNT_OPENED",
                "room-evaluation-account-opened.v1", result.get("payload").toString(),
                result.get("payload_hash").toString(), result.get("producer_idempotency_key").toString(),
                null, 1, OBSERVED_AT, OBSERVED_AT.plusSeconds(30));
        assertThat(results.consume(source, "test", Duration.ofSeconds(30)))
                .isEqualTo(RoomEvaluationAccountResultConsumer.Outcome.OPENED);
    }

    private void seedBacktestParticipation(Instant admittedAt) {
        var at = EVALUATION_START.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, created_by_operator_id, name, access_type, status, created_at) "
                        + "values (?, 'BACKTEST', 'PLATFORM', ?, 'E11 Backtest', 'PUBLIC', 'EVALUATING', ?)",
                ROOM_ID, OPERATOR_ID, at.minusDays(1));
        seedRoomRulesAndSchedule(at);
        jdbc.update(
                "update competition.room_schedules set participation_closes_at = ? where room_id = ?",
                at.plusHours(1), ROOM_ID);
        seedBotAndParticipation(admittedAt.atOffset(ZoneOffset.UTC));
        jdbc.update(
                "insert into bot.launch_contract_plans "
                        + "(bot_id, contract_version, plan_schema_version, plan_checksum, plan_document, created_at) "
                        + "values (?, 'strategy-bot.v1', 'basic-compiled-plan.v1', ?, ?::jsonb, ?)",
                BOT_ID, "sha256:" + "2".repeat(64), featurePlanDocument(), at.minusHours(1));
        jdbc.update(
                "insert into competition.backtest_evaluation_plans "
                        + "(room_id, plan_version, period_count, plan_hash, commitment_hash, "
                        + "commitment_nonce_ciphertext, nonce_key_version, locked_at) "
                        + "values (?, 'competition-plan.v1', 2, ?, ?, 'ciphertext', 1, ?)",
                ROOM_ID, "sha256:" + "3".repeat(64), "sha256:" + "4".repeat(64), at.minusDays(1));
        jdbc.update(
                "insert into backtest.execution_policy_versions "
                        + "(version, policy_artifact_hash, policy_document, locked_at) "
                        + "values ('competition-policy-v1', ?, jsonb_build_object('competitionPlanHash', ?), ?)",
                "a".repeat(64), "sha256:" + "3".repeat(64), at.minusDays(1));
        jdbc.update(
                "insert into competition.backtest_evaluation_periods "
                        + "(id, evaluation_plan_room_id, period_sequence, evaluation_start, evaluation_end, "
                        + "importance_weight, input_set_hash) values "
                        + "(?, ?, 1, '2025-01-01', '2025-06-30', 0.5, ?), "
                        + "(?, ?, 2, '2025-07-01', '2025-12-31', 0.5, ?)",
                FIRST_PERIOD_ID, ROOM_ID, "sha256:" + "8".repeat(64),
                SECOND_PERIOD_ID, ROOM_ID, "sha256:" + "9".repeat(64));
        jdbc.update(
                "insert into competition.backtest_period_datasets "
                        + "(evaluation_period_id, dataset_manifest_id, purpose_code, locked_dataset_hash) values "
                        + "(?, ?, 'MARKET_BARS', ?), (?, ?, 'MARKET_BARS', ?)",
                FIRST_PERIOD_ID, FIRST_DATASET_ID, "sha256:" + "5".repeat(64),
                SECOND_PERIOD_ID, SECOND_DATASET_ID, "sha256:" + "6".repeat(64));
        jdbc.update(
                "insert into market_data.instruments "
                        + "(id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                FEATURE_INSTRUMENT_ID);
        jdbc.update(
                "insert into market_data.pipeline_runs "
                        + "(id, pipeline_code, pipeline_version, idempotency_key, status, input_hash, output_hash, "
                        + "started_at, completed_at) values (?, 'TEST_FEATURE', 'v1', ?, 'SUCCEEDED', ?, ?, ?, ?)",
                FEATURE_PIPELINE_RUN_ID, "competition-feature:" + FEATURE_PIPELINE_RUN_ID,
                "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64), at.minusHours(2), at.minusHours(1));
        jdbc.update("insert into market_data.dataset_manifests "
                        + "(id, feed_id, instrument_id, data_layer, resolution, revision_number, status, period_start, "
                        + "period_end, schema_version, dataset_hash, created_at, available_at) values "
                        + "(?, ?, ?, 'DERIVED', '1m', 1, 'AVAILABLE', '2024-12-31T00:00:00Z', "
                        + "'2026-01-01T00:00:00Z', 'feature-series.parquet.v1', ?, ?, ?)",
                FEATURE_MANIFEST_ID, FEED_ID, FEATURE_INSTRUMENT_ID, "a".repeat(64),
                at.minusHours(2), at.minusHours(1));
        jdbc.update("insert into storage.objects "
                        + "(id, status, storage_provider, bucket_name, object_key, provider_version_id, content_hash, "
                        + "byte_size, file_format, compression_codec, media_type, schema_version, row_count, "
                        + "period_start, period_end, retention_policy_version, created_at, verified_at) values "
                        + "(?, 'AVAILABLE', 'S3', 'test', 'features/competition.parquet', 'v1', ?, 100, 'PARQUET', "
                        + "'SNAPPY', 'application/vnd.apache.parquet', 'feature-series.parquet.v1', 100, "
                        + "'2024-12-31T00:00:00Z', '2026-01-01T00:00:00Z', 'v1', ?, ?)",
                FEATURE_OBJECT_ID, "f".repeat(64), at.minusHours(2), at.minusHours(1));
        jdbc.update("insert into market_data.dataset_objects "
                        + "(id, dataset_manifest_id, object_id, object_kind, partition_granularity, partition_start, "
                        + "partition_end, period_start, period_end, shard_key, part_number, row_count) values "
                        + "(?, ?, ?, 'FEATURE_SERIES', 'YEAR', '2025-01-01', '2026-01-01', "
                        + "'2024-12-31T00:00:00Z', '2026-01-01T00:00:00Z', 'all', 1, 100)",
                FEATURE_DATASET_OBJECT_ID, FEATURE_MANIFEST_ID, FEATURE_OBJECT_ID);
        jdbc.update(
                "insert into market_data.feature_materializations "
                        + "(id, feature_definition_id, instrument_id, pipeline_run_id, input_dataset_set_hash, "
                        + "period_start, period_end, source_watermark, output_dataset_manifest_id, result_hash, "
                        + "status, available_at, created_at) values (?, ?, ?, ?, ?, ?, ?, 'test-watermark', ?, ?, "
                        + "'SUCCEEDED', ?, ?)",
                FEATURE_MATERIALIZATION_ID, UUID.fromString("0f1b0000-0000-4000-8000-000000000001"),
                FEATURE_INSTRUMENT_ID, FEATURE_PIPELINE_RUN_ID, "sha256:" + "e".repeat(64),
                java.time.OffsetDateTime.parse("2024-12-31T00:00:00Z"),
                java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                FEATURE_MANIFEST_ID, "sha256:" + "b".repeat(64), at.minusHours(1), at.minusHours(2));
        jdbc.update(
                "insert into competition.backtest_period_feature_materializations "
                        + "(evaluation_period_id, feature_materialization_id, locked_result_hash) values "
                        + "(?, ?, ?), (?, ?, ?)",
                FIRST_PERIOD_ID, FEATURE_MATERIALIZATION_ID, "sha256:" + "b".repeat(64),
                SECOND_PERIOD_ID, FEATURE_MATERIALIZATION_ID, "sha256:" + "b".repeat(64));
        jdbc.update(
                "update competition.participations set joined_at = ? where id = ?",
                admittedAt.atOffset(ZoneOffset.UTC), PARTICIPATION_ID);
    }

    private void seedRoomRulesAndSchedule(java.time.OffsetDateTime at) {
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, bot_participation_limit, "
                        + "per_account_bot_limit, eligibility_document, market_scope_document, scoring_parameters, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, precision_rules_version, "
                        + "rules_hash, locked_at) values (?, ?, 100000, 10, 2, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, "
                        + "?, 5, ?, 'v1', ?, ?)",
                ROOM_ID, SCORING_ID, FEE_ID, BUFFER_ID, "sha256:" + "7".repeat(64), at.minusDays(1));
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                ROOM_ID, at.minusDays(1), at.minusHours(1), at, at, at.plusHours(2), at.plusHours(3));
    }

    private void seedBotAndParticipation(java.time.OffsetDateTime at) {
        seedBotAndParticipation(BOT_ID, PARTICIPATION_ID, at);
    }

    private void seedBotAndParticipation(
            UUID botId, UUID participationId, java.time.OffsetDateTime at) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'E11 Bot', 'RUNNING', ?, ?, ?, 0, ?)",
                botId, OWNER_ID, at.minusHours(1), at.minusHours(1), at, at.minusHours(1));
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                botId, "1".repeat(64), "2".repeat(64), "3".repeat(64), at.minusHours(1));
        jdbc.update(
                "insert into bot.launch_configurations "
                        + "(bot_id, initial_cash_amount, currency_code, broker_rules_version, accounting_rules_version, "
                        + "precision_rules_version, fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, "
                        + "candidate_conflict_policy, configuration_hash) "
                        + "values (?, 100000, 'USD', 'v1', 'v1', 'v1', ?, 5, ?, '{}'::jsonb, 'config-e11')",
                botId, FEE_ID, BUFFER_ID);
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at) "
                        + "values (?, ?, ?, ?, ?, 'REGISTERED', ?)",
                participationId, ROOM_ID, botId, OWNER_ID, "e11-bot-" + participationId, at.minusHours(1));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("86000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static String featurePlanDocument() {
        return "{\"requiredFeatures\":[{\"requirementId\":\"rsi-14-pt1m\","
                + "\"featureId\":\"0f1b0000-0000-4000-8000-000000000001\","
                + "\"featureVersion\":\"1.0.0\",\"instruments\":[\"" + FEATURE_INSTRUMENT_ID
                + "\"],\"resolution\":\"PT1M\",\"requiredObservations\":14}]}";
    }

    private void seedDataset(UUID datasetId, String hashDigit, java.time.OffsetDateTime at) {
        jdbc.update(
                "insert into market_data.dataset_manifests "
                        + "(id, feed_id, data_layer, resolution, revision_number, status, period_start, period_end, "
                        + "schema_version, dataset_hash, created_at, available_at) "
                        + "values (?, ?, 'ADJUSTED', '1d', 1, 'AVAILABLE', '2025-01-01T00:00:00Z', "
                        + "'2025-12-31T23:59:59Z', 'v1', ?, ?, ?)",
                datasetId, FEED_ID, hashDigit.repeat(64), at.minusDays(2), at.minusDays(2));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({FeatureMaterializationPinResolver.class, RoomEvaluationStartJooqAdapter.class, RoomEvaluationAccountResultConsumer.class,
            TransactionalOutboxStore.class})
    static class TestApplication {}
}
