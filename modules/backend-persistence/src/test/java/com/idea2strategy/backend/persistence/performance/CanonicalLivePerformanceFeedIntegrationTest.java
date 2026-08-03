package com.idea2strategy.backend.persistence.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
 * F93's live feed against the real canonical schema: fills in, a scored official projection out,
 * bounded by the segment and idempotent under replay.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CanonicalLivePerformanceFeedIntegrationTest.TestApplication.class)
class CanonicalLivePerformanceFeedIntegrationTest {

    private static final UUID OWNER = UUID.fromString("10000000-0000-4000-8000-0000000000fe");
    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-0000000000fe");
    private static final UUID PARTITION = UUID.fromString("31000000-0000-4000-8000-0000000000fe");
    private static final UUID INSTRUMENT = UUID.fromString("36000000-0000-4000-8000-0000000000fe");
    private static final UUID FEE_POLICY = UUID.fromString("37000000-0000-4000-8000-0000000000fe");
    private static final UUID BUFFER_POLICY = UUID.fromString("37000000-0000-4000-8000-0000000000ff");
    private static final UUID SCORING_TEMPLATE = UUID.fromString("f9400000-0000-4000-8000-000000000000");
    private static final UUID ROOM = UUID.fromString("f9400000-0000-4000-8000-000000000001");
    private static final UUID PARTICIPATION = UUID.fromString("f9400000-0000-4000-8000-000000000002");
    private static final UUID SEGMENT = UUID.fromString("f9400000-0000-4000-8000-000000000003");

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
    private CanonicalLivePerformanceFeed feed;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("set session_replication_role = replica");
        jdbc.execute("truncate table performance.bot_current_projections, trading.fills,"
                + " trading.orders, competition.live_evaluation_segments,"
                + " competition.participations, competition.room_rules cascade");
        jdbc.update("delete from competition.rooms where id = ?", ROOM);
        jdbc.update("delete from bot.bot_events where bot_id = ?", BOT);
        jdbc.update("delete from bot.bots where id = ?", BOT);
        jdbc.update("delete from identity.accounts where id = ?", OWNER);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_POLICY);
        jdbc.update("delete from market_data.instruments where id = ?", INSTRUMENT);

        jdbc.update("insert into identity.accounts (id, lifecycle_status, status_changed_at,"
                + " created_at) values (?, 'ACTIVE', now(), now())", OWNER);
        jdbc.update("insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status,"
                + " lifecycle_changed_at, created_at, execution_eligible_from)"
                + " values (?, ?, 'BASIC', 'F93 room bot', 'RUNNING', now(), now(), now())",
                BOT, OWNER);
        jdbc.update("insert into market_data.instruments (id, asset_type, primary_exchange_mic,"
                + " currency_code) values (?, 'STOCK', 'XNAS', 'USD')", INSTRUMENT);
        jdbc.update("insert into trading.fee_policy_versions (id, policy_code, version,"
                + " fee_rate_bps, calculation_rules_version, rules_hash, effective_from,"
                + " published_at) values (?, 'OFFICIAL_FEE', 'v1', 20, 'fee-calc:v1', ?,"
                + " now(), now())", FEE_POLICY, "f".repeat(64));

        jdbc.update("insert into competition.rooms (id, competition_type, organizer_type,"
                + " creator_account_id, name, access_type, status, created_at)"
                + " values (?, 'LIVE_PAPER'::competition.competition_type,"
                + " 'USER'::competition.organizer_type, ?, 'F93 room',"
                + " 'PUBLIC'::competition.room_access_type,"
                + " 'EVALUATING'::competition.room_status, now())", ROOM, OWNER);
        jdbc.update("insert into competition.room_rules (room_id, scoring_template_version_id,"
                + " initial_cash_amount, currency_code, bot_participation_limit,"
                + " per_account_bot_limit, eligibility_document, market_scope_document,"
                + " scoring_parameters, fee_policy_id, slippage_rate_bps,"
                + " buying_power_buffer_policy_id, precision_rules_version, rules_hash, locked_at)"
                + " values (?, ?, 1000, 'USD', 10, 1, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, ?,"
                + " 5, ?, 'precision:v1', ?, now())",
                ROOM, SCORING_TEMPLATE, FEE_POLICY, BUFFER_POLICY, "r".repeat(64));
        jdbc.update("insert into competition.participations (id, room_id, bot_id,"
                + " owner_account_id, anonymous_alias, status, joined_at, evaluation_started_at)"
                + " values (?, ?, ?, ?, 'bot-orchid-07',"
                + " 'EVALUATING'::competition.participation_status, now(), now())",
                PARTICIPATION, ROOM, BOT, OWNER);
        jdbc.update("insert into competition.live_evaluation_segments (id, participation_id,"
                + " segment_type, starts_at, ends_at, start_event_sequence, initial_state_hash)"
                + " values (?, ?, 'OFFICIAL_EVALUATION', '2026-08-15T00:00:00+00'::timestamptz,"
                + " '2026-08-29T00:00:00+00'::timestamptz, 1, ?)",
                SEGMENT, PARTICIPATION, "sha256:" + "a".repeat(64));

        // Inside the segment: buy 2 at 10, sell 1 at 12. At the end instant: excluded.
        seedFill(2, "BUY", "2", "10", "2026-08-15T01:00:00Z");
        seedFill(3, "SELL", "1", "12", "2026-08-16T00:00:00Z");
        seedFill(4, "SELL", "1", "99", "2026-08-29T00:00:00Z");
        jdbc.execute("set session_replication_role = origin");
    }

    private void seedFill(int sequence, String side, String quantity, String price, String at) {
        UUID eventId = UUID.nameUUIDFromBytes(("f93feed-event:" + sequence)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID orderId = UUID.nameUUIDFromBytes(("f93feed-order:" + sequence)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID fillId = UUID.nameUUIDFromBytes(("f93feed-fill:" + sequence)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("insert into bot.bot_events (id, bot_id, event_sequence, event_type,"
                + " event_schema_version, correlation_id, idempotency_key, occurred_at,"
                + " received_at, summary_document) values (?, ?, ?, 'ORDER_FILLED', 'v1',"
                + " gen_random_uuid(), ?, ?::timestamptz, ?::timestamptz, '{}')",
                eventId, BOT, sequence, "f93feed-" + sequence, at, at);
        jdbc.update("insert into trading.orders (id, bot_id, partition_id, instrument_id,"
                + " order_key, side, order_type, time_in_force, requested_quantity,"
                + " broker_rules_version, precision_rules_version, slippage_rate_bps,"
                + " fee_policy_id, accepted_event_id, accepted_at, contract_hash)"
                + " values (?, ?, ?, ?, ?, ?::trading.order_side, 'MARKET'::trading.order_type,"
                + " 'DAY'::trading.time_in_force, ?::numeric, 'broker:v1', 'precision:v1', 5, ?,"
                + " ?, ?::timestamptz, ?)",
                orderId, BOT, PARTITION, INSTRUMENT, "order:" + sequence, side, quantity,
                FEE_POLICY, eventId, at, "c".repeat(64));
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
                fillId, orderId, BOT, PARTITION, eventId, "exec-" + sequence, quantity, price, at,
                "m".repeat(64), price, gross, FEE_POLICY, gross, fee, delta, at);
    }

    /**
     * Cash walks to 991.936, the remaining long marks at 12, so equity is 1003.936 and the fill at
     * the segment end never entered — the projection's sequence stops at the last consumed fill.
     */
    @Test
    void segmentFillsProjectIntoTheOfficialLivePerformance() {
        int applied = feed.refreshActiveSegments();

        assertThat(applied).isEqualTo(1);
        var row = jdbc.queryForMap(
                "select equity_amount, total_return_pct, last_event_sequence"
                        + " from performance.bot_current_projections where bot_id = ?", BOT);
        assertThat((BigDecimal) row.get("equity_amount")).isEqualByComparingTo("1003.936");
        assertThat((BigDecimal) row.get("total_return_pct")).isEqualByComparingTo("0.3936");
        assertThat(((Number) row.get("last_event_sequence")).longValue()).isEqualTo(3L);
    }

    /** The same canonical state re-fed moves nothing: replay is the consumer's no-op. */
    @Test
    void refeedingAnUnchangedSegmentAppliesNothing() {
        assertThat(feed.refreshActiveSegments()).isEqualTo(1);
        assertThat(feed.refreshActiveSegments()).isEqualTo(0);

        long rows = jdbc.queryForObject(
                "select count(*) from performance.bot_current_projections where bot_id = ?",
                Long.class, BOT);
        assertThat(rows).isEqualTo(1L);
    }

    /** A finalized segment stops feeding, whatever fills exist. */
    @Test
    void aFinalizedSegmentIsNotFed() {
        jdbc.update("update competition.live_evaluation_segments set finalized_at = now(),"
                + " end_event_sequence = 3, final_state_hash = ?, source_set_hash = ?"
                + " where id = ?", "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64), SEGMENT);

        assertThat(feed.refreshActiveSegments()).isEqualTo(0);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({CanonicalLivePerformanceFeed.class, BotCurrentPerformanceJpaCommandAdapter.class})
    static class TestApplication {
    }
}
