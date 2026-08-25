package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;
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
 * The official Basic catalog exists after migration, and says what the runtimes can execute.
 *
 * <p>Root #193: these tables were created by the baseline and never populated, which every existing
 * test hid by seeding its own rows. This one seeds nothing — it asserts against whatever the
 * migrations produced, so an empty catalog fails the build instead of failing a user.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = SeededBasicElementCatalogTest.TestApplication.class)
class SeededBasicElementCatalogTest {

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
    private JdbcTemplate jdbc;

    @Autowired
    private BasicStrategyCatalogJooqQueryAdapter catalogAdapter;

    @Test
    void publishesOneActiveLiveTimeframeCatalogAndRetiresEarlierCatalogs() {
        List<Map<String, Object>> versions = jdbc.queryForList(
                "select catalog_version, language_version, data_requirement_version, retired_at "
                        + "from strategy.element_catalog_versions");

        assertThat(versions).hasSize(5);
        assertThat(versions).filteredOn(version -> version.get("retired_at") == null).singleElement().satisfies(version -> {
            assertThat(version.get("catalog_version")).isEqualTo("basic-elements:2026-08-25");
            assertThat(version.get("language_version")).isEqualTo("basic/v1");
            assertThat(version.get("data_requirement_version")).isEqualTo("alpaca-sip/v1");
        });
        assertThat(versions).filteredOn(version -> version.get("catalog_version").equals("basic-elements:2026-08-04"))
                .singleElement().satisfies(version -> assertThat(version.get("retired_at")).isNotNull());
        assertThat(versions).filteredOn(version -> version.get("catalog_version").equals("basic-elements:2026-08-07"))
                .singleElement().satisfies(version -> assertThat(version.get("retired_at")).isNotNull());
        assertThat(versions).filteredOn(version -> version.get("catalog_version").equals("basic-elements:2026-08-08-live-bars"))
                .singleElement().satisfies(version -> assertThat(version.get("retired_at")).isNotNull());
        assertThat(versions).filteredOn(version -> version.get("catalog_version").equals("basic-elements:2026-08-08"))
                .singleElement().satisfies(version -> assertThat(version.get("retired_at")).isNotNull());
    }

    @Test
    void resolvesTheExactPublishedCatalogPinnedByAValidationRun() {
        UUID catalogId = jdbc.queryForObject(
                "select id from strategy.element_catalog_versions where retired_at is null",
                UUID.class);

        assertThat(catalogAdapter.findPublishedCatalog(catalogId, Instant.now()))
                .get()
                .extracting(version -> version.id())
                .isEqualTo(catalogId);
    }

    /** Every block exposed by the Basic editor has one executable operation in the active catalog. */
    @Test
    void publishesTheCompleteExecutableEditorCatalog() {
        List<Map<String, Object>> elements = jdbc.queryForList("""
                select element_code, element_kind,
                       execution_contract -> 'runtime' ->> 'operation' as operation,
                       execution_contract -> 'backtest' ->> 'supported' as backtest_supported,
                       execution_contract -> 'reviewTemplates' ->> 'ko-KR' as review
                from strategy.element_definitions element
                join strategy.element_catalog_versions version
                  on version.id = element.element_catalog_version_id
                where version.retired_at is null
                order by element_code
                """);

        assertThat(elements).hasSize(14);
        assertThat(elements).extracting(row -> row.get("element_code"))
                .containsExactly(
                        "BASIC_BOLLINGER_REVERSAL", "BASIC_DRAWDOWN_FROM_PEAK",
                        "BASIC_EQUAL_ALLOCATION_ORDER", "BASIC_HOLDING_PERIOD", "BASIC_MACD_CROSS",
                        "BASIC_PEAK_RETURN", "BASIC_POSITION_RETURN", "BASIC_PRICE_CHANGE_PERCENT",
                        "BASIC_PRICE_COMPARE", "BASIC_RSI_CROSS", "BASIC_SCHEDULE", "BASIC_SMA_CROSS",
                        "BASIC_STREAK", "BASIC_VOLUME_COMPARE");
        assertThat(elements).extracting(row -> row.get("operation"))
                .contains("PRICE_COMPARE", "RSI_CROSS", "MACD_CROSS", "BOLLINGER_REVERSAL",
                        "POSITION_RETURN", "HOLDING_PERIOD", "SCHEDULE", "EMIT_ORDER_CANDIDATE");
        // B08 renders these to users; a missing template is a validation issue, not a blank sentence.
        assertThat(elements).allSatisfy(row -> {
            assertThat(row.get("review")).asString().isNotBlank();
            assertThat(row.get("backtest_supported")).isEqualTo("true");
        });
    }

    /** Position conditions stay sell-only, schedules stay buy-only, and market conditions work on both sides. */
    @Test
    void declaresSafeTradeContainers() {
        List<Map<String, Object>> containers = jdbc.queryForList("""
                select element_code,
                       execution_contract -> 'containers' as containers
                from strategy.element_definitions element
                join strategy.element_catalog_versions version
                  on version.id = element.element_catalog_version_id
                where version.retired_at is null
                """);

        assertThat(containers).hasSize(14);
        assertThat(containers).filteredOn(row -> row.get("element_code").equals("BASIC_SCHEDULE"))
                .singleElement().satisfies(row -> assertThat(row.get("containers").toString()).contains("BUY").doesNotContain("SELL"));
        assertThat(containers).filteredOn(row -> row.get("element_code").equals("BASIC_POSITION_RETURN"))
                .singleElement().satisfies(row -> assertThat(row.get("containers").toString()).contains("SELL").doesNotContain("BUY"));
        assertThat(containers).filteredOn(row -> row.get("element_code").equals("BASIC_PRICE_COMPARE"))
                .singleElement().satisfies(row -> assertThat(row.get("containers").toString()).contains("BUY").contains("SELL"));
    }

    /**
     * The chain types up: the indicator's output port feeds the comparison's input port, and the
     * comparison's output feeds the terminal order. `BasicBlockAssemblyValidator` rejects a
     * connection whose port types differ, so a catalog that did not line up could never compile.
     */
    @Test
    void thePortTypesFormAnExecutableChain() {
        assertThat(portType("BASIC_PRICE_COMPARE", "output_port_schema", "passed"))
                .isEqualTo(portType("BASIC_RSI_CROSS", "input_port_schema", "passed"))
                .isEqualTo(portType("BASIC_EQUAL_ALLOCATION_ORDER", "input_port_schema", "passed"))
                .isEqualTo("boolean");
    }

    /**
     * The threshold is a string, not a JSON number. `LT 30` and `LTE 30` have to differ exactly at
     * the boundary, and a JSON number would arrive as a double.
     */
    @Test
    void theComparisonThresholdIsAnExactDecimalString() {
        assertThat(jdbc.queryForObject("""
                select parameter_schema -> 'properties' -> 'threshold' ->> 'type'
                from strategy.element_definitions element
                join strategy.element_catalog_versions version
                  on version.id = element.element_catalog_version_id
                where element.element_code = 'BASIC_RSI_CROSS'
                  and version.retired_at is null
                """, String.class)).isEqualTo("string");
    }

    @Test
    void exposesOnlyFinalizedStrategyResolutionsToTheEditor() {
        List<String> resolutions = jdbc.queryForList("""
                select distinct jsonb_array_elements_text(
                    parameter_schema -> 'properties' -> 'resolution' -> 'enum')
                from strategy.element_definitions element
                join strategy.element_catalog_versions version
                  on version.id = element.element_catalog_version_id
                where version.retired_at is null
                  and parameter_schema #> '{properties,resolution}' is not null
                """, String.class);

        assertThat(resolutions).containsExactlyInAnyOrder("30m", "1h", "4h", "1d");
    }

    /**
     * The declared feature is registered, and against this catalog version. Without the row
     * `BasicBacktestCapabilityValidator` reports BACKTEST_FEATURE_UNAVAILABLE and no strategy
     * validates, so the two halves of the seed are only correct together.
     */
    @Test
    void registersTheDeclaredFeatureAgainstThisCatalogVersion() {
        assertThat(jdbc.queryForList("""
                select feature.feature_code, feature.calculator_version, feature.resolution,
                       feature.required_history_points, feature.output_value_type
                from market_data.feature_definitions feature
                join strategy.element_catalog_versions version
                  on version.id = feature.element_catalog_version_id
                where version.catalog_version = 'basic-elements:2026-08-04'
                """)).singleElement().satisfies(row -> {
                    assertThat(row.get("feature_code")).isEqualTo("RSI_14");
                    assertThat(row.get("calculator_version")).isEqualTo("rsi:1.0.0");
                    assertThat(row.get("resolution")).isEqualTo("1m");
                    assertThat(row.get("required_history_points")).isEqualTo(15);
                    assertThat(row.get("output_value_type")).isEqualTo("NUMBER");
                });
    }

    /** Only RSI_CROSS consumes the one official historical feature; other blocks stay raw-market operations. */
    @Test
    void declaresOnlyTheOfficialRsiFeatureDependency() {
        List<String> declared = jdbc.queryForList("""
                select element_code || ':' || feature
                from strategy.element_definitions element
                join strategy.element_catalog_versions version
                  on version.id = element.element_catalog_version_id
                cross join lateral jsonb_array_elements_text(
                    execution_contract -> 'backtest' -> 'features') feature
                where version.retired_at is null
                order by 1
                """, String.class);

        assertThat(declared).containsExactly("BASIC_RSI_CROSS:RSI_14");
    }

    @Test
    void publishesOnlyProductionResolutionsWithoutPerElementBacktestFeeds() {
        List<String> resolutionEnums = jdbc.queryForList("""
                select distinct jsonb_array_elements_text(parameter_schema #> '{properties,resolution,enum}')
                from strategy.element_definitions element
                join strategy.element_catalog_versions version
                  on version.id = element.element_catalog_version_id
                where version.retired_at is null
                  and parameter_schema #> '{properties,resolution,enum}' is not null
                order by 1
                """, String.class);
        assertThat(resolutionEnums).containsExactly("1d", "1h", "30m", "4h");

        Integer feedDeclarations = jdbc.queryForObject("""
                select count(*)
                from strategy.element_definitions element
                join strategy.element_catalog_versions version
                  on version.id = element.element_catalog_version_id
                where version.retired_at is null
                  and execution_contract #> '{backtest,feeds}' is not null
                """, Integer.class);
        assertThat(feedDeclarations).isZero();

        UUID activeCatalog = jdbc.queryForObject(
                "select id from strategy.element_catalog_versions where retired_at is null",
                UUID.class);
        // V1 is the post-AWS launch baseline, so its canonical feature definitions are present
        // directly and must cover every production resolution exposed by the active catalog.
        assertThat(catalogAdapter.findFeatures(activeCatalog))
                .hasSize(4)
                .extracting(definition -> definition.resolution())
                .containsExactlyInAnyOrder("30m", "1h", "4h", "1d");

        assertThat(jdbc.queryForList("""
                select jsonb_array_elements_text(parameter_schema #> '{properties,executionMode,enum}')
                from strategy.element_definitions
                where element_catalog_version_id = '0f4a0000-0000-4000-8000-000000000001'::uuid
                  and element_code = 'BASIC_EQUAL_ALLOCATION_ORDER'
                order by 1
                """, String.class)).containsExactly(
                        "1회만", "대기 후 재실행", "대기 후 재진입", "주기마다");
    }

    private String portType(String elementCode, String column, String port) {
        return jdbc.queryForObject(
                "select element." + column + " -> ? ->> 'type' from strategy.element_definitions element "
                        + "join strategy.element_catalog_versions version on version.id = element.element_catalog_version_id "
                        + "where version.retired_at is null and element.element_code = ?",
                String.class, port, elementCode);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BasicStrategyCatalogJooqQueryAdapter.class)
    static class TestApplication {}
}
