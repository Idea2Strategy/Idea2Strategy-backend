package com.idea2strategy.backend.persistence.bottrading;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Reads the canonical trading tables the trading engine writes, against the real schema.
 *
 * <p>The rows are seeded with referential triggers off. This adapter is a read path, so standing up
 * a consistent write history through the trading engine's own services would prove nothing extra
 * here and would couple this test to that engine's version. What has to be true is that the columns
 * exist, the joins reach them, and the ownership check refuses another account.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BotTradingJooqQueryAdapterIntegrationTest.TestApplication.class)
class BotTradingJooqQueryAdapterIntegrationTest {

    private static final UUID OWNER = UUID.fromString("10000000-0000-4000-8000-00000000000a");
    private static final UUID OTHER_OWNER = UUID.fromString("10000000-0000-4000-8000-00000000000b");
    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-00000000000a");
    private static final UUID PARTITION = UUID.fromString("31000000-0000-4000-8000-00000000000a");
    private static final UUID FLOW = UUID.fromString("32000000-0000-4000-8000-00000000000a");
    private static final UUID INSTRUMENT = UUID.fromString("36000000-0000-4000-8000-00000000000a");
    private static final UUID FEE_POLICY = UUID.fromString("37000000-0000-4000-8000-00000000000a");
    private static final UUID EVENT = UUID.fromString("33000000-0000-4000-8000-00000000000a");
    private static final UUID EVALUATION = UUID.fromString("34000000-0000-4000-8000-00000000000a");
    private static final UUID BATCH = UUID.fromString("38000000-0000-4000-8000-00000000000a");
    private static final UUID ORDER = UUID.fromString("39000000-0000-4000-8000-00000000000a");
    private static final UUID FILL = UUID.fromString("3a000000-0000-4000-8000-00000000000a");
    private static final UUID REDUCED_INTENT = UUID.fromString("3b000000-0000-4000-8000-00000000000a");
    private static final UUID APPROVED_INTENT = UUID.fromString("3b000000-0000-4000-8000-00000000000b");
    private static final UUID CLOSE_ACTION = UUID.fromString("3c000000-0000-4000-8000-00000000000a");
    private static final String HASH = "a".repeat(64);
    private static final String AT = "2026-08-03T12:00:00+00";

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
    private BotTradingJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("set session_replication_role = replica");
        jdbc.execute("truncate table trading.system_close_actions, trading.fills, "
                + "trading.order_state_projections, trading.orders, trading.order_intents, "
                + "trading.order_intent_batches, trading.flow_position_projections, "
                + "trading.bot_budget_projections, trading.partition_budget_projections cascade");
        jdbc.update("delete from bot.bots where id = ?", BOT);
        jdbc.update("delete from identity.accounts where id in (?, ?)", OWNER, OTHER_OWNER);

        insertAccount(OWNER);
        insertAccount(OTHER_OWNER);
        jdbc.update("insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,"
                + " lifecycle_changed_at, created_at, execution_eligible_from)"
                + " values (?, ?, 'BASIC', 'Trading bot', 'RUNNING', ?::timestamptz,"
                + " ?::timestamptz, ?::timestamptz)", BOT, OWNER, AT, AT, AT);

        jdbc.update("insert into trading.orders (id, bot_id, partition_id, instrument_id, order_key,"
                + " side, order_type, time_in_force, requested_quantity, broker_rules_version,"
                + " precision_rules_version, slippage_rate_bps, fee_policy_id, accepted_event_id,"
                + " accepted_at, contract_hash) values (?, ?, ?, ?, 'order:1',"
                + " 'BUY'::trading.order_side, 'MARKET'::trading.order_type,"
                + " 'DAY'::trading.time_in_force, 3, 'broker:v1', 'precision:v1', 5, ?, ?,"
                + " ?::timestamptz, ?)",
                ORDER, BOT, PARTITION, INSTRUMENT, FEE_POLICY, EVENT, AT, HASH);
        jdbc.update("insert into trading.order_state_projections (order_id, bot_id, partition_id,"
                + " status, filled_quantity, remaining_quantity, reserved_cash, reserved_quantity,"
                + " last_order_event_sequence, last_bot_event_sequence, updated_at)"
                + " values (?, ?, ?, 'FILLED'::trading.order_status, 3, 0, 0, 0, 2, 2, ?::timestamptz)",
                ORDER, BOT, PARTITION, AT);
        jdbc.update("insert into trading.fills (id, order_id, bot_id, partition_id, bot_event_id,"
                + " provider_fill_key, quantity, reference_price, reference_observed_at,"
                + " reference_market_hash, slippage_rate_bps, slippage_amount, fill_price,"
                + " gross_amount, fee_policy_id, fee_rate_bps, precision_rules_version,"
                + " fee_basis_amount, fee_amount, settlement_cash_delta, occurred_at)"
                + " values (?, ?, ?, ?, ?, 'exec-1', 3, 10, ?::timestamptz, ?, 5, 0.01, 10, 30, ?,"
                + " 20, 'precision:v1', 30, 0.06, -30.06, ?::timestamptz)",
                FILL, ORDER, BOT, PARTITION, EVENT, AT, HASH, FEE_POLICY, AT);

        jdbc.update("insert into trading.flow_position_projections (flow_id, partition_id, bot_id,"
                + " instrument_id, long_quantity, short_quantity, cost_basis_amount,"
                + " last_event_sequence, projection_hash, updated_at)"
                + " values (?, ?, ?, ?, 3, 0, 30.06, 4, ?, ?::timestamptz)",
                FLOW, PARTITION, BOT, INSTRUMENT, HASH, AT);
        jdbc.update("insert into trading.bot_budget_projections (bot_id, currency_code,"
                + " available_cash_amount, active_reservation_amount, invested_amount,"
                + " segregated_short_proceeds_amount, short_collateral_amount, valuation_at,"
                + " valuation_status, last_event_sequence, projection_hash, updated_at)"
                + " values (?, 'USD', 969.94, 0, 30.06, 0, 0, ?::timestamptz, 'VALUED', 4, ?,"
                + " ?::timestamptz)", BOT, AT, HASH, AT);
        jdbc.update("insert into trading.partition_budget_projections (partition_id, bot_id,"
                + " currency_code, budget_cap_amount, active_reservation_amount, invested_amount,"
                + " segregated_short_proceeds_amount, short_collateral_amount, valuation_at,"
                + " valuation_status, last_event_sequence, projection_hash, updated_at)"
                + " values (?, ?, 'USD', 1000, 0, 30.06, 0, 0, ?::timestamptz, 'VALUED', 4, ?,"
                + " ?::timestamptz)", PARTITION, BOT, AT, HASH, AT);

        jdbc.update("insert into trading.order_intent_batches (id, bot_id, partition_id,"
                + " source_event_id, status, conflict_policy_hash, composition_rules_version,"
                + " input_state_hash, result_hash, finalized_at)"
                + " values (?, ?, ?, ?, 'FINALIZED', ?, 'composition:v1', ?, ?, ?::timestamptz)",
                BATCH, BOT, PARTITION, EVENT, HASH, HASH, HASH, AT);
        insertIntent(REDUCED_INTENT, "REDUCED", "BUDGET_CAP_EXCEEDED", 5, 3);
        insertIntent(APPROVED_INTENT, "APPROVED", "ELIGIBLE", 3, 3);

        jdbc.update("insert into trading.system_close_actions (id, bot_id, partition_id, flow_id,"
                + " instrument_id, source_event_id, reason_type, requested_quantity,"
                + " generated_intent_id, reason_document, calculation_hash, created_at)"
                + " values (?, ?, ?, ?, ?, ?, 'BOT_STOP'::trading.system_close_reason, 3, ?,"
                + " '{}'::jsonb, ?, ?::timestamptz)",
                CLOSE_ACTION, BOT, PARTITION, FLOW, INSTRUMENT, EVENT, REDUCED_INTENT, HASH, AT);
        jdbc.execute("set session_replication_role = origin");
    }

    @Test
    void ordersCarryTheProjectedStateRatherThanTheImmutableOrderRow() {
        var orders = adapter.findOwnedOrders(BOT, OWNER, 50).orElseThrow();

        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst().orderId()).isEqualTo(ORDER);
        assertThat(orders.getFirst().status()).isEqualTo("FILLED");
        assertThat(orders.getFirst().filledQuantity()).isEqualByComparingTo("3");
        assertThat(orders.getFirst().remainingQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void everyFillIsItsOwnRowWithTheInstrumentTakenFromItsOrder() {
        var fills = adapter.findOwnedFills(BOT, OWNER, 50).orElseThrow();

        assertThat(fills).hasSize(1);
        assertThat(fills.getFirst().fillId()).isEqualTo(FILL);
        assertThat(fills.getFirst().instrumentId()).isEqualTo(INSTRUMENT);
        assertThat(fills.getFirst().fillPrice()).isEqualByComparingTo("10");
        assertThat(fills.getFirst().settlementCashDelta()).isEqualByComparingTo("-30.06");
    }

    @Test
    void positionsReportLongAndShortSeparately() {
        var positions = adapter.findOwnedPositions(BOT, OWNER).orElseThrow();

        assertThat(positions).hasSize(1);
        assertThat(positions.getFirst().longQuantity()).isEqualByComparingTo("3");
        assertThat(positions.getFirst().shortQuantity()).isEqualByComparingTo("0");
        assertThat(positions.getFirst().costBasisAmount()).isEqualByComparingTo("30.06");
    }

    @Test
    void theBudgetCarriesItsPartitions() {
        var budget = adapter.findOwnedBudget(BOT, OWNER).orElseThrow();

        assertThat(budget.currencyCode()).isEqualTo("USD");
        assertThat(budget.availableCashAmount()).isEqualByComparingTo("969.94");
        assertThat(budget.partitions()).hasSize(1);
        assertThat(budget.partitions().getFirst().budgetCapAmount()).isEqualByComparingTo("1000");
    }

    /** An owned bot that has not traded has no projection row, which is not the same as no bot. */
    @Test
    void anOwnedBotWithoutAProjectionAnswersUnvalued() {
        jdbc.execute("delete from trading.bot_budget_projections");
        jdbc.execute("delete from trading.partition_budget_projections");

        var budget = adapter.findOwnedBudget(BOT, OWNER).orElseThrow();

        assertThat(budget.valuationStatus()).isEqualTo("UNVALUED");
        assertThat(budget.partitions()).isEmpty();
    }

    /** Only what was refused or cut down; an intent that went through whole explains nothing. */
    @Test
    void decisionReasonsCoverOnlyRefusedOrReducedIntents() {
        var reasons = adapter.findOwnedDecisionReasons(BOT, OWNER, 50).orElseThrow();

        assertThat(reasons).hasSize(1);
        assertThat(reasons.getFirst().intentId()).isEqualTo(REDUCED_INTENT);
        assertThat(reasons.getFirst().decision()).isEqualTo("REDUCED");
        assertThat(reasons.getFirst().reasonCode()).isEqualTo("BUDGET_CAP_EXCEEDED");
        assertThat(reasons.getFirst().requestedQuantity()).isEqualByComparingTo("5");
        assertThat(reasons.getFirst().finalQuantity()).isEqualByComparingTo("3");
    }

    @Test
    void stopSettlementNamesTheIntentEachActionGenerated() {
        var actions = adapter.findOwnedStopSettlement(BOT, OWNER).orElseThrow();

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().reasonType()).isEqualTo("BOT_STOP");
        assertThat(actions.getFirst().generatedIntentId()).isEqualTo(REDUCED_INTENT);
    }

    /**
     * The bot id comes from the client, so every surface has to refuse another account rather than
     * answer for it. Absent, not empty: an empty list would say the bot exists and did nothing.
     */
    @Test
    void anotherAccountIsRefusedOnEverySurface() {
        assertThat(adapter.findOwnedOrders(BOT, OTHER_OWNER, 50)).isEmpty();
        assertThat(adapter.findOwnedFills(BOT, OTHER_OWNER, 50)).isEmpty();
        assertThat(adapter.findOwnedPositions(BOT, OTHER_OWNER)).isEmpty();
        assertThat(adapter.findOwnedBudget(BOT, OTHER_OWNER)).isEmpty();
        assertThat(adapter.findOwnedDecisionReasons(BOT, OTHER_OWNER, 50)).isEmpty();
        assertThat(adapter.findOwnedStopSettlement(BOT, OTHER_OWNER)).isEmpty();
    }

    private void insertAccount(UUID id) {
        jdbc.update("insert into identity.accounts (id, lifecycle_status, status_changed_at,"
                + " created_at) values (?, 'ACTIVE', ?::timestamptz, ?::timestamptz)", id, AT, AT);
    }

    /**
     * {@code flow_intent_requires_evaluation} is a check constraint, and check constraints survive
     * {@code session_replication_role = replica}, so the evaluation run has to be named even though
     * the foreign keys are off.
     */
    private void insertIntent(UUID id, String decision, String reason, int requested, int finalQty) {
        jdbc.update("insert into trading.order_intents (id, bot_id, batch_id, source_event_id,"
                + " origin_type, evaluation_run_id, partition_id, flow_id, instrument_id,"
                + " intent_key, side, position_effect, order_type, time_in_force,"
                + " requested_quantity, post_netting_quantity, final_quantity, decision,"
                + " decision_reason_code)"
                + " values (?, ?, ?, ?, 'FLOW_EVALUATION'::trading.intent_origin_type, ?, ?, ?, ?,"
                + " ?, 'BUY'::trading.order_side, 'OPEN_LONG'::trading.position_effect,"
                + " 'MARKET'::trading.order_type, 'DAY'::trading.time_in_force, ?, ?, ?,"
                + " ?::trading.intent_decision, ?)",
                id, BOT, BATCH, EVENT, EVALUATION, PARTITION, FLOW, INSTRUMENT, "candidate:" + id,
                requested, finalQty, finalQty, decision, reason);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BotTradingJooqQueryAdapter.class)
    static class TestApplication {
    }
}
