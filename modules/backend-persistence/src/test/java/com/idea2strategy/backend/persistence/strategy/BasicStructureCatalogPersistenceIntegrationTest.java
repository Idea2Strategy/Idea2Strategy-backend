package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.BasicStrategyCatalog;
import com.idea2strategy.backend.application.strategy.BasicStructureCatalogQueryService;
import com.idea2strategy.backend.application.strategy.BasicStructureKind;
import com.idea2strategy.backend.application.strategy.BasicStructureVersion;
import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
@SpringBootTest(classes = BasicStructureCatalogPersistenceIntegrationTest.TestApplication.class)
class BasicStructureCatalogPersistenceIntegrationTest {
    private static final UUID CATALOG_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_CATALOG_ID = UUID.fromString("61000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-01T05:00:00Z");

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
    private BasicStructureCatalogJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedStructures() {
        jdbcTemplate.update("delete from strategy.package_versions");
        jdbcTemplate.update("delete from strategy.packages");
        jdbcTemplate.update("delete from strategy.element_definitions");
        jdbcTemplate.update("delete from strategy.element_catalog_versions");
        insertCatalog(CATALOG_ID, "a".repeat(64));
        insertCatalog(OTHER_CATALOG_ID, "b".repeat(64));
        jdbcTemplate.update(
                """
                insert into strategy.element_definitions
                    (id, element_catalog_version_id, element_code, element_kind, parameter_schema,
                     input_port_schema, output_port_schema, execution_contract, definition_hash)
                values (?, ?, 'RSI', 'BLOCK', cast(? as jsonb), cast('{}' as jsonb), cast('{}' as jsonb),
                        cast(? as jsonb), ?)
                """,
                UUID.randomUUID(),
                CATALOG_ID,
                "{\"type\":\"object\",\"properties\":{\"period\":{\"type\":\"integer\"}}}",
                "{\"containers\":[\"BUY\",\"SELL\"]}",
                "c".repeat(64));

        insertStructure("BUY_DIRECTION", BasicStructureKind.BUY_TEMPLATE, "BUY", CATALOG_ID, "ACTIVE", null, null);
        insertStructure("SELL_DIRECTION", BasicStructureKind.SELL_TEMPLATE, "SELL", CATALOG_ID, "ACTIVE", null, null);
        insertStructure("RSI_STRUCTURE", BasicStructureKind.PACKAGE, "BUY", CATALOG_ID, "ACTIVE", null, null);
        insertStructure("INACTIVE", BasicStructureKind.PACKAGE, "BUY", CATALOG_ID, "INACTIVE", null, null);
        insertStructure("RETIRED", BasicStructureKind.PACKAGE, "BUY", CATALOG_ID, "ACTIVE", NOW.minusSeconds(1), null);
        insertStructure("FUTURE", BasicStructureKind.PACKAGE, "BUY", CATALOG_ID, "ACTIVE", null, NOW.plusSeconds(60));
        insertStructure("OTHER_CATALOG", BasicStructureKind.PACKAGE, "BUY", OTHER_CATALOG_ID, "ACTIVE", null, null);
    }

    @Test
    void readsOnlyActivePublishedStructuresForTheExactCatalogVersion() {
        var service = new BasicStructureCatalogQueryService(adapter, Clock.fixed(NOW, ZoneOffset.UTC));

        List<BasicStructureVersion> structures = service.getPublished(catalog());

        assertThat(structures)
                .extracting(BasicStructureVersion::code)
                .containsExactly("BUY_DIRECTION", "SELL_DIRECTION", "RSI_STRUCTURE");
    }

    private void insertCatalog(UUID id, String hash) {
        jdbcTemplate.update(
                """
                insert into strategy.element_catalog_versions
                    (id, language_version, schema_version, catalog_version, data_requirement_version,
                     definition_hash, published_at)
                values (?, 'basic/v1', 'schema/v1', ?, 'data/v1', ?, ?)
                """,
                id,
                id.equals(CATALOG_ID) ? "catalog/v1" : "catalog/v2",
                hash,
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC));
    }

    private void insertStructure(
            String code,
            BasicStructureKind kind,
            String container,
            UUID catalogId,
            String status,
            Instant retiredAt,
            Instant publishedAt) {
        UUID packageId = UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String document = StrategyDocumentJson.canonicalize(
                "{\"mode\":\"BASIC\",\"kind\":\"" + kind + "\",\"container\":\"" + container + "\","
                        + "\"instrumentIds\":[],\"blocks\":[{\"id\":\"indicator\","
                        + "\"elementCode\":\"RSI\",\"parameters\":{\"period\":null}}],"
                        + "\"connections\":[]}");
        jdbcTemplate.update(
                "insert into strategy.packages (id, code, status, created_at) values (?, ?, ?, ?)",
                packageId,
                code,
                status,
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                """
                insert into strategy.package_versions
                    (id, package_id, version, element_catalog_version_id, name_i18n, description_i18n,
                     flow_document, content_hash, published_at, retired_at)
                values (?, ?, '1.0.0', ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?, ?)
                """,
                UUID.nameUUIDFromBytes((code + ":version").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                packageId,
                catalogId,
                "{\"ko\":\"" + code + "\",\"en\":\"" + code + "\"}",
                "{\"ko\":\"구조 설명\",\"en\":\"Structure description\"}",
                document,
                StrategyDocumentJson.sha256(document),
                (publishedAt == null ? NOW.minusSeconds(60) : publishedAt).atOffset(ZoneOffset.UTC),
                retiredAt == null ? null : retiredAt.atOffset(ZoneOffset.UTC));
    }

    private static BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID, "basic/v1", "schema/v1", "catalog/v1", "data/v1",
                        "a".repeat(64), NOW.minusSeconds(3600), null),
                List.of(new StrategyElementDefinition(
                        UUID.randomUUID(), CATALOG_ID, "RSI", "BLOCK", "{}", "{}", "{}",
                        "{\"containers\":[\"BUY\",\"SELL\"]}", "c".repeat(64))),
                List.of(),
                List.of());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BasicStructureCatalogJooqQueryAdapter.class)
    static class TestApplication {}
}
