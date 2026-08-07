package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.BasicStrategyCatalog;
import com.idea2strategy.backend.application.strategy.BasicStructureCandidate;
import com.idea2strategy.backend.application.strategy.BasicStructureCatalogQueryService;
import com.idea2strategy.backend.application.strategy.StrategyCopyCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestSessionPrincipal;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
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
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = StrategyCopyPersistenceIntegrationTest.TestApplication.class)
class StrategyCopyPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID SOURCE_ID = UUID.fromString("72000000-0000-4000-8000-000000000001");
    private static final UUID COPY_ID = UUID.fromString("72000000-0000-4000-8000-000000000002");
    private static final UUID CATALOG_ID = UUID.fromString("73000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE_VERSION_ID = UUID.fromString("74000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T08:30:00Z");

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
    private BasicStrategyDraftJpaCommandAdapter draftCommandAdapter;

    @Autowired
    private StrategyJooqQueryAdapter strategyQueryAdapter;

    @Autowired
    private StrategyDocumentJooqQueryAdapter documentQueryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareOwnerAndSource() {
        jdbcTemplate.update("delete from strategy.strategy_edit_leases");
        jdbcTemplate.update("delete from strategy.validation_runs");
        jdbcTemplate.update("delete from strategy.strategy_documents");
        jdbcTemplate.update("delete from strategy.strategies");
        jdbcTemplate.execute(
                "truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbcTemplate.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                OWNER_ID,
                NOW.atOffset(ZoneOffset.UTC));

        Strategy source = Strategy.createBasic(SOURCE_ID, OWNER_ID, "Source", "Original", NOW.minusSeconds(60));
        String semantic = "{\"catalogId\":\"" + CATALOG_ID + "\",\"groups\":[],\"mode\":\"BASIC\"}";
        String presentation = "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
        draftCommandAdapter.create(
                source,
                StrategyDocument.create(
                        SOURCE_ID,
                        semantic,
                        presentation,
                        "basic-semantic/v1",
                        "basic-presentation/v1",
                        StrategyDocumentJson.sha256(semantic),
                        StrategyDocumentJson.sha256(presentation),
                        NOW.minusSeconds(60)));
    }

    @Test
    void persistsStrategyAndPackageCopiesAsIndependentDraftsOnly() {
        var strategyCopy = service(COPY_ID, List.of());
        UUID copiedStrategyId = strategyCopy.copyOwnedStrategy(SOURCE_ID);

        UUID packageCopyId = UUID.fromString("72000000-0000-4000-8000-000000000003");
        var packageCopy = service(packageCopyId, structures());
        packageCopy.copyBasicPackage(catalog(), PACKAGE_VERSION_ID, "Package copy", null);

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from strategy.strategies where owner_account_id = ?",
                        Integer.class,
                        OWNER_ID))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                        "select edit_sequence from strategy.strategy_documents where strategy_id = ?",
                        Long.class,
                        copiedStrategyId))
                .isZero();
        String packageDocument = jdbcTemplate.queryForObject(
                "select semantic_document::text from strategy.strategy_documents where strategy_id = ?",
                String.class,
                packageCopyId);
        assertThat(packageDocument)
                .contains("\"catalogId\": \"" + CATALOG_ID + "\"")
                .doesNotContain(PACKAGE_VERSION_ID.toString())
                .doesNotContain("packageId");
        assertThat(jdbcTemplate.queryForObject("select count(*) from bot.bots", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from trading.ledger_entries", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from performance.bot_snapshots", Integer.class))
                .isZero();
    }

    private StrategyCopyCommandService service(UUID generatedId, List<BasicStructureCandidate> structures) {
        return new StrategyCopyCommandService(
                draftCommandAdapter,
                strategyQueryAdapter,
                documentQueryAdapter,
                new BasicStructureCatalogQueryService(
                        (catalogId, publishedAt) -> structures,
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                new TestSessionPrincipal(OWNER_ID),
                new FixedIdGenerator(generatedId),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new RecordingDomainEventPublisher());
    }

    private static List<BasicStructureCandidate> structures() {
        return List.of(
                structure(UUID.fromString("74000000-0000-4000-8000-000000000010"), "BUY_TEMPLATE", "BUY"),
                structure(UUID.fromString("74000000-0000-4000-8000-000000000011"), "SELL_TEMPLATE", "SELL"),
                structure(PACKAGE_VERSION_ID, "PACKAGE", "BUY"));
    }

    private static BasicStructureCandidate structure(UUID id, String kind, String container) {
        String code = kind.equals("PACKAGE") ? "RSI_PACKAGE" : kind;
        String flow = StrategyDocumentJson.canonicalize(
                "{\"mode\":\"BASIC\",\"kind\":\"" + kind + "\",\"container\":\"" + container + "\","
                        + "\"instrumentIds\":[],\"blocks\":[{\"id\":\"" + code + "-indicator\","
                        + "\"elementCode\":\"RSI\",\"parameters\":{\"period\":null}}],\"connections\":[]}");
        return new BasicStructureCandidate(
                id,
                UUID.randomUUID(),
                code,
                "1.0.0",
                CATALOG_ID,
                "{\"ko\":\"구조\",\"en\":\"Structure\"}",
                "{\"ko\":\"설명\",\"en\":\"Description\"}",
                flow,
                StrategyDocumentJson.sha256(flow),
                NOW.minusSeconds(30));
    }

    private static BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID,
                        "basic/v1",
                        "schema/v1",
                        "catalog/v1",
                        "data/v1",
                        "a".repeat(64),
                        NOW.minusSeconds(60),
                        null),
                List.of(new StrategyElementDefinition(
                        UUID.randomUUID(), CATALOG_ID, "RSI", "BLOCK", "{}", "{}", "{}", "{}", "b".repeat(64))),
                List.of(),
                List.of());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {StrategyJpaEntity.class, StrategyDocumentJpaEntity.class})
    @EnableJpaRepositories(basePackageClasses = {
        StrategySpringDataRepository.class,
        StrategyDocumentSpringDataRepository.class
    })
    @Import({
        BasicStrategyDraftJpaCommandAdapter.class,
        StrategyJooqQueryAdapter.class,
        StrategyDocumentJooqQueryAdapter.class
    })
    static class TestApplication {}
}
