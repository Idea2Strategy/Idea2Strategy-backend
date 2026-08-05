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
    void publishesExactlyOneCatalogVersion() {
        List<Map<String, Object>> versions = jdbc.queryForList(
                "select catalog_version, language_version, data_requirement_version, retired_at "
                        + "from strategy.element_catalog_versions");

        assertThat(versions).singleElement().satisfies(version -> {
            assertThat(version.get("catalog_version")).isEqualTo("basic-elements:2026-08-04");
            assertThat(version.get("language_version")).isEqualTo("basic/v1");
            assertThat(version.get("data_requirement_version")).isEqualTo("alpaca-sip/v1");
            assertThat(version.get("retired_at")).isNull();
        });
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

    /** Exactly the three operations both runtimes implement, and nothing a runtime would refuse. */
    @Test
    void publishesTheThreeExecutableOperations() {
        List<Map<String, Object>> elements = jdbc.queryForList("""
                select element_code, element_kind,
                       execution_contract -> 'runtime' ->> 'operation' as operation,
                       execution_contract -> 'backtest' ->> 'supported' as backtest_supported,
                       execution_contract -> 'reviewTemplates' ->> 'ko-KR' as review
                from strategy.element_definitions
                order by element_code
                """);

        assertThat(elements).hasSize(3);
        assertThat(elements).extracting(row -> row.get("element_code"))
                .containsExactly("BASIC_EQUAL_ALLOCATION_ORDER", "BASIC_RSI_READ", "BASIC_VALUE_COMPARE");
        assertThat(elements).extracting(row -> row.get("operation"))
                .containsExactly("EMIT_ORDER_CANDIDATE", "LOAD_FEATURE", "COMPARE");
        // B08 renders these to users; a missing template is a validation issue, not a blank sentence.
        assertThat(elements).allSatisfy(row -> {
            assertThat(row.get("review")).asString().isNotBlank();
            assertThat(row.get("backtest_supported")).isEqualTo("true");
        });
    }

    /** Every element may sit in either container, so one catalog serves buy and sell flows. */
    @Test
    void everyElementDeclaresBothTradeContainers() {
        List<Map<String, Object>> containers = jdbc.queryForList("""
                select element_code,
                       execution_contract -> 'containers' as containers
                from strategy.element_definitions
                """);

        assertThat(containers).hasSize(3);
        assertThat(containers).allSatisfy(row ->
                assertThat(row.get("containers").toString()).contains("BUY").contains("SELL"));
    }

    /**
     * The chain types up: the indicator's output port feeds the comparison's input port, and the
     * comparison's output feeds the terminal order. `BasicBlockAssemblyValidator` rejects a
     * connection whose port types differ, so a catalog that did not line up could never compile.
     */
    @Test
    void thePortTypesFormAnExecutableChain() {
        assertThat(portType("BASIC_RSI_READ", "output_port_schema", "value"))
                .isEqualTo(portType("BASIC_VALUE_COMPARE", "input_port_schema", "value"))
                .isEqualTo("number");
        assertThat(portType("BASIC_VALUE_COMPARE", "output_port_schema", "passed"))
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
                from strategy.element_definitions where element_code = 'BASIC_VALUE_COMPARE'
                """, String.class)).isEqualTo("string");
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

    /** The only feature any element declares is the one both runtimes implement. */
    @Test
    void declaresOnlyTheImplementedFeature() {
        List<String> declared = jdbc.queryForList("""
                select jsonb_array_elements_text(execution_contract -> 'backtest' -> 'features')
                from strategy.element_definitions
                """, String.class);

        assertThat(declared).containsExactly("RSI_14");
    }

    private String portType(String elementCode, String column, String port) {
        return jdbc.queryForObject(
                "select " + column + " -> ? ->> 'type' from strategy.element_definitions "
                        + "where element_code = ?",
                String.class, port, elementCode);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BasicStrategyCatalogJooqQueryAdapter.class)
    static class TestApplication {}
}
