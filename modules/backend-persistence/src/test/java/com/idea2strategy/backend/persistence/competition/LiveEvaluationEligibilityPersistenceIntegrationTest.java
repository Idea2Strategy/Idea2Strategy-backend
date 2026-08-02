package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.LiveEvaluationEligibilityNotFoundException;
import com.idea2strategy.backend.application.competition.LiveEvaluationIneligibilityReason;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
@SpringBootTest(classes = LiveEvaluationEligibilityPersistenceIntegrationTest.TestApplication.class)
class LiveEvaluationEligibilityPersistenceIntegrationTest {
    private static final UUID OWNER_ID = id(1);
    private static final UUID ROOM_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID PARTICIPATION_ID = id(4);
    private static final UUID SEGMENT_ID = id(5);
    private static final UUID PARTITION_ID = id(6);
    private static final UUID INSTRUMENT_ID = id(7);
    private static final UUID FEE_POLICY_ID = id(8);
    private static final Instant SEGMENT_START = Instant.parse("2026-08-02T05:00:00Z");

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

    @Autowired LiveEvaluationEligibilityJooqAdapter adapter;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from trading.fills");
        jdbc.update("delete from trading.orders");
        jdbc.update("delete from bot.bot_events");
        jdbc.update("delete from bot.bot_partitions");
        jdbc.update("delete from competition.live_evaluation_segments");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.live_room_rules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from market_data.instruments where id = ?", INSTRUMENT_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_POLICY_ID);
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'Eligibility room', 'PUBLIC', 'EVALUATING', ?)",
                ROOM_ID, OWNER_ID, utc(SEGMENT_START.minusSeconds(3600)));
        jdbc.update(
                "insert into competition.live_room_rules "
                        + "(room_id, stopped_bot_slot_policy, minimum_operation_seconds, minimum_fill_count) "
                        + "values (?, 'COUNT_UNTIL_END', 300, 1)",
                ROOM_ID);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'Eligibility bot', 'RUNNING', ?, ?, ?, 0, ?)",
                BOT_ID, OWNER_ID, utc(SEGMENT_START), utc(SEGMENT_START),
                utc(SEGMENT_START.minusSeconds(3600)), utc(SEGMENT_START));
        jdbc.update(
                "insert into bot.bot_partitions "
                        + "(id, bot_id, name, budget_cap_bps, position_x, position_y, configuration_hash) "
                        + "values (?, ?, 'Eligibility partition', 10000, 0, 0, 'eligibility-partition')",
                PARTITION_ID, BOT_ID);
        jdbc.update(
                "insert into market_data.instruments "
                        + "(id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                INSTRUMENT_ID);
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'OFFICIAL', '1', 20, 'v1', "
                        + "'eligibility-fee-policy', ?, ?)",
                FEE_POLICY_ID, utc(SEGMENT_START.minusSeconds(3600)), utc(SEGMENT_START.minusSeconds(3600)));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, evaluation_started_at) "
                        + "values (?, ?, ?, ?, 'eligibility-bot', 'EVALUATING', ?, ?)",
                PARTICIPATION_ID, ROOM_ID, BOT_ID, OWNER_ID,
                utc(SEGMENT_START.minusSeconds(1800)), utc(SEGMENT_START));
        jdbc.update(
                "insert into competition.live_evaluation_segments "
                        + "(id, participation_id, segment_type, starts_at, ends_at, start_event_sequence, initial_state_hash) "
                        + "values (?, ?, 'OFFICIAL_EVALUATION', ?, ?, 1, 'eligibility-initial-state')",
                SEGMENT_ID, PARTICIPATION_ID, utc(SEGMENT_START), utc(SEGMENT_START.plusSeconds(600)));
    }

    @Test
    void countsWallClockWithinTheOfficialSegmentAndRequiresFillRowsIndependently() {
        seedFill(1, SEGMENT_START.plusSeconds(120));
        seedFill(2, SEGMENT_START.plusSeconds(600));
        var beforeOperationBoundary = adapter.evaluate(PARTICIPATION_ID, SEGMENT_START.plusSeconds(299));
        var atOperationBoundary = adapter.evaluate(PARTICIPATION_ID, SEGMENT_START.plusSeconds(300));
        var afterSegment = adapter.evaluate(PARTICIPATION_ID, SEGMENT_START.plusSeconds(900));

        assertThat(beforeOperationBoundary.operationSeconds()).isEqualTo(299);
        assertThat(beforeOperationBoundary.fillCount()).isEqualTo(1);
        assertThat(beforeOperationBoundary.reasons())
                .containsExactly(LiveEvaluationIneligibilityReason.MINIMUM_OPERATION_NOT_MET);
        assertThat(atOperationBoundary.operationSeconds()).isEqualTo(300);
        assertThat(atOperationBoundary.eligible()).isTrue();
        assertThat(afterSegment.operationSeconds()).isEqualTo(600);
        assertThat(afterSegment.fillCount()).isEqualTo(1);
    }

    @Test
    void treatsZeroThresholdsAsSatisfiedAndRejectsUnknownParticipations() {
        jdbc.update(
                "update competition.live_room_rules set minimum_operation_seconds = 0, minimum_fill_count = 0 "
                        + "where room_id = ?",
                ROOM_ID);

        assertThat(adapter.evaluate(PARTICIPATION_ID, SEGMENT_START).eligible()).isTrue();
        assertThatThrownBy(() -> adapter.evaluate(id(99), SEGMENT_START))
                .isInstanceOf(LiveEvaluationEligibilityNotFoundException.class);
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private void seedFill(int suffix, Instant occurredAt) {
        UUID acceptedEventId = id(100 + suffix * 10);
        UUID fillEventId = id(101 + suffix * 10);
        UUID orderId = id(102 + suffix * 10);
        jdbc.update(
                "insert into bot.bot_events "
                        + "(id, bot_id, event_sequence, event_type, event_schema_version, correlation_id, "
                        + "idempotency_key, occurred_at, received_at, summary_document) values "
                        + "(?, ?, ?, 'ORDER_ACCEPTED', 'trading.v1', ?, ?, ?, ?, '{}'::jsonb), "
                        + "(?, ?, ?, 'FILL_RECORDED', 'trading.v1', ?, ?, ?, ?, '{}'::jsonb)",
                acceptedEventId, BOT_ID, suffix * 2L - 1, id(200 + suffix * 10), "accepted-" + suffix,
                utc(occurredAt.minusSeconds(1)), utc(occurredAt.minusSeconds(1)),
                fillEventId, BOT_ID, suffix * 2L, id(201 + suffix * 10), "fill-" + suffix,
                utc(occurredAt), utc(occurredAt));
        jdbc.update(
                "insert into trading.orders "
                        + "(id, bot_id, partition_id, instrument_id, order_key, side, order_type, time_in_force, "
                        + "requested_quantity, broker_rules_version, precision_rules_version, slippage_rate_bps, "
                        + "fee_policy_id, accepted_event_id, accepted_at, contract_hash) "
                        + "values (?, ?, ?, ?, ?, 'BUY', 'MARKET', 'DAY', 10, 'v1', 'v1', 5, ?, ?, ?, ?)",
                orderId, BOT_ID, PARTITION_ID, INSTRUMENT_ID, "order-" + suffix,
                FEE_POLICY_ID, acceptedEventId, utc(occurredAt.minusSeconds(1)), "order-hash-" + suffix);
        jdbc.update(
                "insert into trading.fills "
                        + "(id, order_id, bot_id, partition_id, bot_event_id, provider_fill_key, quantity, "
                        + "reference_price, reference_observed_at, reference_market_hash, slippage_rate_bps, "
                        + "slippage_amount, fill_price, gross_amount, fee_policy_id, fee_rate_bps, "
                        + "precision_rules_version, fee_basis_amount, fee_amount, settlement_cash_delta, occurred_at) "
                        + "values (?, ?, ?, ?, ?, ?, 10, 100, ?, ?, 5, 0.5, 100.05, 1000.5, ?, 20, "
                        + "'v1', 1000.5, 2.001, -1002.501, ?)",
                id(103 + suffix * 10), orderId, BOT_ID, PARTITION_ID, fillEventId, "provider-fill-" + suffix,
                utc(occurredAt), "market-hash-" + suffix, FEE_POLICY_ID, utc(occurredAt));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a8000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(LiveEvaluationEligibilityJooqAdapter.class)
    static class TestApplication {}
}
