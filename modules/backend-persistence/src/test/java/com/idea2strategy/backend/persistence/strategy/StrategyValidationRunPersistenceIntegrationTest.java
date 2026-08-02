package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = StrategyValidationRunPersistenceIntegrationTest.TestApplication.class)
class StrategyValidationRunPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID CATALOG_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T02:00:00Z");

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
    private StrategyJpaCommandAdapter strategyCommandAdapter;

    @Autowired
    private StrategyValidationRunJpaCommandAdapter validationCommandAdapter;

    @Autowired
    private StrategyValidationRunJooqQueryAdapter validationQueryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareReferences() {
        jdbcTemplate.update("delete from strategy.validation_runs");
        jdbcTemplate.update("delete from strategy.strategies");
        jdbcTemplate.update("delete from strategy.element_catalog_versions where id = ?", CATALOG_ID);
        jdbcTemplate.execute(
                "truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbcTemplate.update("delete from identity.accounts where id in (?, ?)", OWNER_ID, OTHER_OWNER_ID);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?), (?, 'ACTIVE', ?)",
                OWNER_ID,
                NOW.atOffset(ZoneOffset.UTC),
                OTHER_OWNER_ID,
                NOW.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                "insert into strategy.element_catalog_versions "
                        + "(id, language_version, schema_version, catalog_version, data_requirement_version, "
                        + "definition_hash, published_at) values (?, ?, ?, ?, ?, ?, ?)",
                CATALOG_ID,
                "basic/v1",
                "schema/v1",
                "catalog/v1",
                "data/v1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NOW.atOffset(ZoneOffset.UTC));
        strategyCommandAdapter.save(Strategy.createBasic(STRATEGY_ID, OWNER_ID, "Momentum", null, NOW));
    }

    @Test
    void resultJsonRoundTripsAndOwnershipIsEnforced() {
        var run = new StrategyValidationRun(
                RUN_ID,
                STRATEGY_ID,
                OWNER_ID,
                null,
                3,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                CATALOG_ID,
                StrategyValidationStatus.VALID,
                List.of(new StrategyValidationFinding(
                        StrategyValidationFinding.Severity.WARNING,
                        "BACKTEST_FEATURE_UNAVAILABLE",
                        "groups[0].blocks[1].elementCode",
                        "Exact historical feature is unavailable",
                        List.of("feature:RSI_14"))),
                NOW,
                NOW.plusSeconds(1));

        validationCommandAdapter.save(run);

        assertThat(validationQueryAdapter.findOwnedById(RUN_ID, OWNER_ID)).contains(run);
        assertThat(validationQueryAdapter.findOwnedById(RUN_ID, OTHER_OWNER_ID)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                        "select jsonb_typeof(result_document) from strategy.validation_runs where id = ?",
                        String.class,
                        RUN_ID))
                .isEqualTo("object");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {StrategyJpaEntity.class, StrategyValidationRunJpaEntity.class})
    @EnableJpaRepositories(basePackageClasses = {
        StrategySpringDataRepository.class,
        StrategyValidationRunSpringDataRepository.class
    })
    @Import({
        StrategyJpaCommandAdapter.class,
        StrategyValidationRunJpaCommandAdapter.class,
        StrategyValidationRunJooqQueryAdapter.class
    })
    static class TestApplication {}
}
