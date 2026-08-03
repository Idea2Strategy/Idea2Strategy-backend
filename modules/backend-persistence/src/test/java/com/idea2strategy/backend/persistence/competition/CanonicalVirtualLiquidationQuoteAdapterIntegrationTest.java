package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.competition.VirtualLiquidationContext;
import com.idea2strategy.backend.application.competition.VirtualLiquidationPerformance;
import com.idea2strategy.backend.application.competition.VirtualLiquidationPerformanceCalculator;
import com.idea2strategy.backend.application.competition.VirtualLiquidationQuote;
import java.math.BigDecimal;
import java.time.Instant;
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
 * F93's quote against the real canonical schema, judged by E24's own calculator.
 *
 * <p>The strongest assertion here is not a field check but the consumer itself:
 * {@link VirtualLiquidationPerformanceCalculator#calculate} verifies the identity boundary, the
 * fee and slippage pins, the quote hash, and the proceeds-minus-cost-and-fee conservation before
 * it accepts anything. A quote that passes it is a quote E can score.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CanonicalVirtualLiquidationQuoteAdapterIntegrationTest.TestApplication.class)
class CanonicalVirtualLiquidationQuoteAdapterIntegrationTest {

    private static final UUID OWNER = UUID.fromString("10000000-0000-4000-8000-0000000000f9");
    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-0000000000f9");
    private static final UUID PARTITION = UUID.fromString("31000000-0000-4000-8000-0000000000f9");
    private static final UUID INSTRUMENT = UUID.fromString("36000000-0000-4000-8000-0000000000f9");
    private static final UUID PRIOR_INSTRUMENT = UUID.fromString("36000000-0000-4000-8000-0000000000fa");
    private static final UUID FEE_POLICY = UUID.fromString("37000000-0000-4000-8000-0000000000f9");
    private static final UUID ROOM = UUID.fromString("f9300000-0000-4000-8000-000000000001");
    private static final UUID PARTICIPATION = UUID.fromString("f9300000-0000-4000-8000-000000000002");
    private static final UUID SEGMENT = UUID.fromString("f9300000-0000-4000-8000-000000000003");

    private static final Instant STARTS_AT = Instant.parse("2026-08-15T00:00:00Z");
    private static final Instant ENDS_AT = Instant.parse("2026-08-29T00:00:00Z");
    private static final String HASH = "sha256:" + "a".repeat(64);

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
    private CanonicalVirtualLiquidationQuoteAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("set session_replication_role = replica");
        jdbc.execute("truncate table trading.fills, trading.orders cascade");
        jdbc.update("delete from bot.bot_events where bot_id = ?", BOT);
        jdbc.update("delete from bot.bots where id = ?", BOT);
        jdbc.update("delete from identity.accounts where id = ?", OWNER);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_POLICY);
        jdbc.update("delete from market_data.instruments where id in (?, ?)",
                INSTRUMENT, PRIOR_INSTRUMENT);

        jdbc.update("insert into identity.accounts (id, lifecycle_status, status_changed_at,"
                + " created_at) values (?, 'ACTIVE', ?::timestamptz, ?::timestamptz)",
                OWNER, "2026-08-01T00:00:00+00", "2026-08-01T00:00:00+00");
        jdbc.update("insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,"
                + " lifecycle_changed_at, created_at, execution_eligible_from)"
                + " values (?, ?, 'BASIC', 'F93 bot', 'RUNNING', ?::timestamptz, ?::timestamptz,"
                + " ?::timestamptz)",
                BOT, OWNER, "2026-08-01T00:00:00+00", "2026-08-01T00:00:00+00",
                "2026-08-01T00:00:00+00");
        for (UUID instrument : new UUID[] {INSTRUMENT, PRIOR_INSTRUMENT}) {
            jdbc.update("insert into market_data.instruments (id, asset_type,"
                    + " primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                    instrument);
        }
        jdbc.update("insert into trading.fee_policy_versions (id, policy_code, version,"
                + " fee_rate_bps, calculation_rules_version, rules_hash, effective_from,"
                + " published_at) values (?, 'OFFICIAL_FEE', 'v1', 20, 'fee-calc:v1', ?,"
                + " '2026-01-01T00:00:00+00', '2026-01-01T00:00:00+00')",
                FEE_POLICY, "f".repeat(64));

        // Before the segment: the bot bought 1 PRIOR at 50 — a position carried into the segment
        // whose mark can only come from the engine-wide latest reference price.
        seedFill(1, PRIOR_INSTRUMENT, "BUY", "1", "50", "2026-08-10T00:00:00Z");
        // Inside the segment: buy 2 at 10 (cash -20.04), sell 1 at 12 (cash +11.976).
        seedFill(2, INSTRUMENT, "BUY", "2", "10", "2026-08-15T01:00:00Z");
        seedFill(3, INSTRUMENT, "SELL", "1", "12", "2026-08-16T00:00:00Z");
        // At the cutoff instant: excluded by the half-open boundary, or the quote lies.
        seedFill(4, INSTRUMENT, "SELL", "1", "99", "2026-08-29T00:00:00Z");
        jdbc.execute("set session_replication_role = origin");
    }

    private void seedFill(
            int sequence, UUID instrument, String side, String quantity, String price,
            String occurredAt) {
        UUID eventId = UUID.nameUUIDFromBytes(("f93-event:" + sequence)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID orderId = UUID.nameUUIDFromBytes(("f93-order:" + sequence)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID fillId = UUID.nameUUIDFromBytes(("f93-fill:" + sequence)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("insert into bot.bot_events (id, bot_id, event_sequence, event_type,"
                + " event_schema_version, correlation_id, idempotency_key, occurred_at,"
                + " received_at, summary_document) values (?, ?, ?, 'ORDER_FILLED', 'v1',"
                + " gen_random_uuid(), ?, ?::timestamptz, ?::timestamptz, '{}')",
                eventId, BOT, sequence, "f93-seed-" + sequence, occurredAt, occurredAt);
        jdbc.update("insert into trading.orders (id, bot_id, partition_id, instrument_id,"
                + " order_key, side, order_type, time_in_force, requested_quantity,"
                + " broker_rules_version, precision_rules_version, slippage_rate_bps,"
                + " fee_policy_id, accepted_event_id, accepted_at, contract_hash)"
                + " values (?, ?, ?, ?, ?, ?::trading.order_side, 'MARKET'::trading.order_type,"
                + " 'DAY'::trading.time_in_force, ?::numeric, 'broker:v1', 'precision:v1', 5, ?,"
                + " ?, ?::timestamptz, ?)",
                orderId, BOT, PARTITION, instrument, "order:" + sequence, side, quantity,
                FEE_POLICY, eventId, occurredAt, "c".repeat(64));
        BigDecimal gross = new BigDecimal(quantity).multiply(new BigDecimal(price));
        BigDecimal fee = gross.multiply(new BigDecimal("0.002"));
        BigDecimal delta = "BUY".equals(side) ? gross.add(fee).negate() : gross.subtract(fee);
        jdbc.update("insert into trading.fills (id, order_id, bot_id, partition_id, bot_event_id,"
                + " provider_fill_key, quantity, reference_price, reference_observed_at,"
                + " reference_market_hash, slippage_rate_bps, slippage_amount, fill_price,"
                + " gross_amount, fee_policy_id, fee_rate_bps, precision_rules_version,"
                + " fee_basis_amount, fee_amount, settlement_cash_delta, occurred_at)"
                + " values (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?::timestamptz, ?, 5, 0.01,"
                + " ?::numeric, ?, ?, 20, 'precision:v1', ?, ?, ?, ?::timestamptz)",
                fillId, orderId, BOT, PARTITION, eventId, "exec-" + sequence, quantity, price,
                occurredAt, "m".repeat(64), price, gross, FEE_POLICY, gross, fee, delta,
                occurredAt);
    }

    /**
     * E24's calculator is the judge: identity boundary, fee and slippage pins, quote hash and the
     * conservation equation all hold, and the half-open cutoff excluded the fill at the end
     * instant — the source sequence stops at 3.
     */
    @Test
    void theQuotePassesTheConsumersOwnVerification() {
        VirtualLiquidationContext context = context();

        VirtualLiquidationQuote quote = adapter.load(context);
        VirtualLiquidationPerformance performance =
                new VirtualLiquidationPerformanceCalculator().calculate(context, quote);

        // The liquidation is the pseudo-event after the last consumed fill (sequence 3), because
        // the calculator requires every equity observation strictly before the source sequence.
        assertThat(quote.sourceEventSequence()).isEqualTo(4L);
        assertThat(quote.liquidatedPositionCount())
                .as("1 remaining long in-segment plus the carried-in position")
                .isEqualTo(2);
        // cash = 1000 - 20.04 + 11.976 = 991.936; both remainders liquidate at their marks.
        assertThat(quote.currentCashAmount()).isEqualByComparingTo("991.936");
        assertThat(quote.netLiquidationCashDelta()).isEqualByComparingTo(
                quote.grossProceedsAmount().subtract(quote.grossCostAmount())
                        .subtract(quote.feeAmount()));
        assertThat(performance.equityAmount()).isEqualByComparingTo(
                quote.currentCashAmount().add(quote.netLiquidationCashDelta()));
        assertThat(quote.equityHistory()).hasSize(2);
    }

    /** The same canonical state quotes identically, hash and all — replay changes nothing. */
    @Test
    void theQuoteIsDeterministic() {
        VirtualLiquidationQuote first = adapter.load(context());
        VirtualLiquidationQuote second = adapter.load(context());

        assertThat(second).isEqualTo(first);
        assertThat(second.quoteHash()).isEqualTo(first.quoteHash());
    }

    /** The carried-in position is marked by the engine-wide latest official reference price. */
    @Test
    void aPositionCarriedIntoTheSegmentIsMarkedByItsLatestOfficialPrice() {
        VirtualLiquidationQuote quote = adapter.load(context());

        // PRIOR: 1 long at mark 50 → proceeds 50 * (1 - 0.0005) = 49.975
        // IN-SEGMENT remainder: 1 long at mark 12 → proceeds 12 * (1 - 0.0005) = 11.994
        assertThat(quote.grossProceedsAmount()).isEqualByComparingTo("61.969");
        assertThat(quote.grossCostAmount()).isEqualByComparingTo("0");
    }

    private static VirtualLiquidationContext context() {
        return new VirtualLiquidationContext(
                ROOM, PARTICIPATION, BOT, SEGMENT, STARTS_AT, ENDS_AT, 1L,
                new BigDecimal("1000"), FEE_POLICY, HASH, 5, HASH);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(CanonicalVirtualLiquidationQuoteAdapter.class)
    static class TestApplication {
    }
}
