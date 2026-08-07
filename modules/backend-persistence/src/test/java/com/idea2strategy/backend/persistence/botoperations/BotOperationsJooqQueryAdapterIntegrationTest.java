package com.idea2strategy.backend.persistence.botoperations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
@SpringBootTest(classes = BotOperationsJooqQueryAdapterIntegrationTest.TestApplication.class)
class BotOperationsJooqQueryAdapterIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID PARTITION_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID FLOW_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID CATALOG_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

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
    private BotOperationsJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareData() {
        jdbc.update("delete from bot.bot_events");
        jdbc.update("delete from bot.flow_instruments where flow_id = ?", FLOW_ID);
        jdbc.update("delete from bot.flows where id = ?", FLOW_ID);
        jdbc.update("delete from bot.bot_partitions where id = ?", PARTITION_ID);
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from strategy.compiled_flow_plans where id = ?", PLAN_ID);
        jdbc.update("delete from strategy.element_catalog_versions where id = ?", CATALOG_ID);
        jdbc.update("delete from market_data.instrument_symbols where instrument_id = ?", INSTRUMENT_ID);
        jdbc.update("delete from market_data.instruments where id = ?", INSTRUMENT_ID);
        jdbc.execute("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id in (?, ?)", OWNER_ID, OTHER_OWNER_ID);
        insertAccount(OWNER_ID);
        insertAccount(OTHER_OWNER_ID);
        insertBot(BOT_ID, OWNER_ID, "Owner bot");
        insertBot(OTHER_BOT_ID, OTHER_OWNER_ID, "Other bot");
        insertReleasedInstrument();
        insertEvent(BOT_ID, 2, "BOT_EVALUATED", "{\"decision\":\"BUY\"}");
        insertEvent(BOT_ID, 1, "BOT_TRIGGERED", "{\"trigger\":\"BAR\"}");
        insertEvent(OTHER_BOT_ID, 1, "BOT_EVALUATED", "{\"decision\":\"SELL\"}");
    }

    @Test
    void listsOnlyOwnedBotsAndTracksLatestSequence() {
        var bots = adapter.findOwnedBots(OWNER_ID);

        assertThat(bots).hasSize(1);
        assertThat(bots.getFirst().botId()).isEqualTo(BOT_ID);
        assertThat(bots.getFirst().lastEventSequence()).isEqualTo(2L);
        assertThat(bots.getFirst().instruments()).singleElement().satisfies(instrument -> {
            assertThat(instrument.instrumentId()).isEqualTo(INSTRUMENT_ID);
            assertThat(instrument.symbol()).isEqualTo("AAPL");
        });
    }

    @Test
    void pagesOwnedEventsInSequenceOrderAndRejectsAnotherOwner() {
        var firstPage = adapter.findOwnedJudgments(BOT_ID, OWNER_ID, 0, 1).orElseThrow();
        var secondPage = adapter.findOwnedJudgments(BOT_ID, OWNER_ID, 1, 10).orElseThrow();

        assertThat(firstPage.entries()).extracting(entry -> entry.sequence()).containsExactly(1L);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(secondPage.entries()).extracting(entry -> entry.sequence()).containsExactly(2L);
        assertThat(secondPage.entries().getFirst().summary()).containsEntry("decision", "BUY");
        assertThat(adapter.findOwnedJudgments(OTHER_BOT_ID, OWNER_ID, 0, 10)).isEmpty();
    }

    private void insertAccount(UUID accountId) {
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                accountId,
                NOW.atOffset(ZoneOffset.UTC));
    }

    private void insertBot(UUID botId, UUID ownerId, String name) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, ?)",
                botId,
                ownerId,
                name,
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC));
    }

    private void insertEvent(UUID botId, long sequence, String type, String summary) {
        jdbc.update(
                "insert into bot.bot_events "
                        + "(id, bot_id, event_sequence, event_type, event_schema_version, correlation_id, "
                        + "idempotency_key, occurred_at, received_at, summary_document) "
                        + "values (?, ?, ?, ?, '1', ?, ?, ?, ?, cast(? as jsonb))",
                UUID.randomUUID(),
                botId,
                sequence,
                type,
                UUID.randomUUID(),
                botId + ":" + sequence,
                NOW.plusSeconds(sequence).atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(sequence).atOffset(ZoneOffset.UTC),
                summary);
    }

    private void insertReleasedInstrument() {
        var now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into market_data.instruments (id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                INSTRUMENT_ID);
        jdbc.update(
                "insert into market_data.instrument_symbols (instrument_id, exchange_mic, symbol, effective_from) values (?, 'XNAS', 'AAPL', ?)",
                INSTRUMENT_ID, now.minusDays(1));
        jdbc.update(
                "insert into strategy.element_catalog_versions (id, language_version, schema_version, catalog_version, data_requirement_version, definition_hash, published_at) values (?, 'basic/v1', 'schema/v1', 'catalog/v1', 'data/v1', ?, ?)",
                CATALOG_ID, "1".repeat(64), now.minusDays(1));
        jdbc.update(
                "insert into strategy.compiled_flow_plans (id, element_catalog_version_id, semantic_hash, compiler_version, required_feature_set_hash, plan_document, plan_hash, created_at) values (?, ?, ?, 'compiler/v1', ?, '{}'::jsonb, ?, ?)",
                PLAN_ID, CATALOG_ID, "2".repeat(64), "3".repeat(64), "4".repeat(64), now);
        jdbc.update(
                "insert into bot.bot_partitions (id, bot_id, name, budget_cap_bps, position_x, position_y, configuration_hash, created_at, updated_at) values (?, ?, 'Main', 10000, 0, 0, ?, ?, ?)",
                PARTITION_ID, BOT_ID, "5".repeat(64), now, now);
        jdbc.update(
                "insert into bot.flows (id, partition_id, name, element_catalog_version_id, compiled_flow_plan_id, position_x, position_y, semantic_document, layout_document, layout_schema_version, semantic_hash, layout_hash, configuration_hash, created_at, updated_at) values (?, ?, 'RSI', ?, ?, 0, 0, '{}'::jsonb, '{}'::jsonb, '1', ?, ?, ?, ?, ?)",
                FLOW_ID, PARTITION_ID, CATALOG_ID, PLAN_ID, "6".repeat(64), "7".repeat(64), "8".repeat(64), now, now);
        jdbc.update("insert into bot.flow_instruments (flow_id, instrument_id) values (?, ?)", FLOW_ID, INSTRUMENT_ID);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({BotOperationsJooqQueryAdapter.class, ObjectMapper.class})
    static class TestApplication {}
}
