package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.domain.strategy.CompiledFlowPlan;
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
@SpringBootTest(classes = CompiledFlowPlanPersistenceIntegrationTest.TestApplication.class)
class CompiledFlowPlanPersistenceIntegrationTest {
    private static final UUID CATALOG_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID FIRST_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_ID = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
    private static final String SEMANTIC_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String FEATURE_HASH =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String PLAN_HASH =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

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
    private CompiledFlowPlanJooqCommandAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareCatalog() {
        jdbcTemplate.update("delete from strategy.compiled_flow_plans");
        jdbcTemplate.update("delete from strategy.element_catalog_versions where id = ?", CATALOG_ID);
        jdbcTemplate.update(
                "insert into strategy.element_catalog_versions "
                        + "(id, language_version, schema_version, catalog_version, data_requirement_version, "
                        + "definition_hash, published_at) values (?, ?, ?, ?, ?, ?, ?)",
                CATALOG_ID,
                "basic/v1",
                "schema/v1",
                "catalog/v1",
                "data/v1",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void reusesTheContentAddressedPlanForTheSameCatalogMeaningAndCompiler() {
        var first = plan(FIRST_ID, NOW);
        var duplicate = plan(SECOND_ID, NOW.plusSeconds(1));

        assertThat(adapter.saveOrFind(first)).isEqualTo(first);
        assertThat(adapter.saveOrFind(duplicate)).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from strategy.compiled_flow_plans", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select jsonb_typeof(plan_document) from strategy.compiled_flow_plans where id = ?",
                        String.class,
                        FIRST_ID))
                .isEqualTo("object");
    }

    private static CompiledFlowPlan plan(UUID id, Instant createdAt) {
        return new CompiledFlowPlan(
                id,
                CATALOG_ID,
                SEMANTIC_HASH,
                "basic-compiler:1.0.0",
                FEATURE_HASH,
                "{\"schemaVersion\":\"basic-compiled-plan.v1\"}",
                PLAN_HASH,
                createdAt);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(CompiledFlowPlanJooqCommandAdapter.class)
    static class TestApplication {}
}
