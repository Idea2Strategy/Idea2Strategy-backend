package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.StrategyLibraryItem;
import com.idea2strategy.backend.application.strategy.StrategyLibraryItemKind;
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
@SpringBootTest(classes = StrategyLibraryPersistenceIntegrationTest.TestApplication.class)
class StrategyLibraryPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID DRAFT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_DRAFT_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID PACKAGE_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE_VERSION_ID = UUID.fromString("41000000-0000-4000-8000-000000000001");
    private static final UUID TEMPLATE_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID TEMPLATE_VERSION_ID = UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID CATALOG_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID FEE_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private StrategyLibraryJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedLibrary() {
        jdbc.update("delete from backtest.runs");
        jdbc.update("delete from strategy.validation_runs");
        jdbc.update("delete from strategy.package_versions");
        jdbc.update("delete from strategy.packages");
        jdbc.update("delete from strategy.template_versions");
        jdbc.update("delete from strategy.templates");
        jdbc.update("delete from bot.bots where id in (?, ?)", BOT_ID, OTHER_BOT_ID);
        jdbc.update("delete from strategy.strategies where id in (?, ?)", DRAFT_ID, OTHER_DRAFT_ID);
        jdbc.update("delete from strategy.element_catalog_versions where id = ?", CATALOG_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_ID);
        jdbc.execute(
                "truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id in (?, ?)", OWNER_ID, OTHER_OWNER_ID);

        var now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?), (?, 'ACTIVE', ?)",
                OWNER_ID, now, OTHER_OWNER_ID, now);
        jdbc.update(
                "insert into strategy.element_catalog_versions (id, language_version, schema_version, catalog_version, data_requirement_version, definition_hash, published_at) values (?, 'basic/v1', 'schema/v1', 'catalog/v1', 'data/v1', ?, ?)",
                CATALOG_ID, "a".repeat(64), now.minusMinutes(10));
        jdbc.update(
                "insert into strategy.strategies (id, owner_account_id, mode, name, description, created_at, updated_at) values (?, ?, 'BASIC', 'Owned draft', 'private', ?, ?), (?, ?, 'PRO', 'Other draft', 'must not leak', ?, ?)",
                DRAFT_ID, OWNER_ID, now.minusMinutes(2), now.minusSeconds(10),
                OTHER_DRAFT_ID, OTHER_OWNER_ID, now.minusMinutes(2), now.minusSeconds(5));
        jdbc.update(
                "insert into strategy.validation_runs (id, strategy_id, requested_by_account_id, requested_edit_sequence, semantic_hash, element_catalog_version_id, status, issue_count, result_document, requested_at, completed_at) values (?, ?, ?, 0, ?, ?, 'PASSED', 0, '{}'::jsonb, ?, ?)",
                UUID.randomUUID(), DRAFT_ID, OWNER_ID, "b".repeat(64), CATALOG_ID,
                now.minusSeconds(9), now.minusSeconds(8));
        jdbc.update(
                "insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, execution_eligible_from, created_at, updated_at) values (?, ?, 'PRO', 'Owned release', 'STOPPED', ?, ?, ?, ?), (?, ?, 'BASIC', 'Other release', 'RUNNING', ?, ?, ?, ?)",
                BOT_ID, OWNER_ID, now.minusSeconds(20), now.minusSeconds(20), now.minusMinutes(2), now.minusSeconds(20),
                OTHER_BOT_ID, OTHER_OWNER_ID, now.minusSeconds(35), now.minusSeconds(35), now.minusMinutes(2), now.minusSeconds(35));
        jdbc.update(
                "insert into trading.fee_policy_versions (id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, effective_from, published_at) values (?, 'OFFICIAL', '1', 20, '1', ?, ?, ?)",
                FEE_ID, "c".repeat(64), now.minusDays(1), now.minusDays(1));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions (id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, effective_from, published_at) values (?, 'DEFAULT', '1', 100, '1', ?, ?, ?)",
                BUFFER_ID, "d".repeat(64), now.minusDays(1), now.minusDays(1));
        jdbc.update(
                "insert into backtest.runs (id, bot_id, owner_account_id, configuration_hash, status, evaluation_start, evaluation_end, initial_cash_amount, market_rules_version, accounting_rules_version, precision_rules_version, fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, idempotency_key, queued_at, completed_at, result_hash) values (?, ?, ?, ?, 'COMPLETED', '2026-01-01', '2026-06-30', 100000, '1', '1', '1', ?, 5, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), BOT_ID, OWNER_ID, "e".repeat(64), FEE_ID, BUFFER_ID,
                "library-test", now.minusSeconds(19), now.minusSeconds(18), "f".repeat(64));
        jdbc.update(
                "insert into strategy.packages (id, code, status, created_at) values (?, 'MOMENTUM', 'ACTIVE', ?)",
                PACKAGE_ID, now.minusMinutes(2));
        jdbc.update(
                "insert into strategy.package_versions (id, package_id, version, element_catalog_version_id, name_i18n, description_i18n, flow_document, content_hash, published_at) values (?, ?, '1.0.0', ?, '{\"ko\":\"모멘텀 패키지\"}'::jsonb, '{\"en\":\"Momentum package\"}'::jsonb, '{}'::jsonb, ?, ?)",
                PACKAGE_VERSION_ID, PACKAGE_ID, CATALOG_ID, "1".repeat(64), now.minusSeconds(30));
        jdbc.update(
                "insert into strategy.templates (id, code, status, created_at) values (?, 'PAIR', 'ACTIVE', ?)",
                TEMPLATE_ID, now.minusMinutes(2));
        jdbc.update(
                "insert into strategy.template_versions (id, template_id, version, element_catalog_version_id, name_i18n, description_i18n, semantic_skeleton, content_hash, published_at) values (?, ?, '2.0.0', ?, '{\"en\":\"Pair template\"}'::jsonb, '{}'::jsonb, '{}'::jsonb, ?, ?)",
                TEMPLATE_VERSION_ID, TEMPLATE_ID, CATALOG_ID, "2".repeat(64), now.minusSeconds(40));
    }

    @Test
    void returnsOnlyOwnedPrivateItemsAndActivePublicCatalogEntriesWithStatuses() {
        var items = adapter.findVisible(OWNER_ID, NOW, null, 10);

        assertThat(items).extracting(StrategyLibraryItem::id)
                .containsExactly(DRAFT_ID, BOT_ID, PACKAGE_VERSION_ID, TEMPLATE_VERSION_ID);
        assertThat(items).extracting(StrategyLibraryItem::kind)
                .containsExactly(
                        StrategyLibraryItemKind.DRAFT,
                        StrategyLibraryItemKind.RELEASED,
                        StrategyLibraryItemKind.PACKAGE,
                        StrategyLibraryItemKind.TEMPLATE);
        assertThat(items.get(0).validationStatus()).isEqualTo("PASSED");
        assertThat(items.get(1).backtestStatus()).isEqualTo("COMPLETED");
        assertThat(items.get(2).name()).isEqualTo("모멘텀 패키지");
        assertThat(items).extracting(StrategyLibraryItem::id)
                .doesNotContain(OTHER_DRAFT_ID, OTHER_BOT_ID);

        var afterRelease = adapter.findVisible(OWNER_ID, NOW, items.get(1).position(), 10);
        assertThat(afterRelease).extracting(StrategyLibraryItem::id)
                .containsExactly(PACKAGE_VERSION_ID, TEMPLATE_VERSION_ID);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(StrategyLibraryJooqQueryAdapter.class)
    static class TestApplication {}
}
