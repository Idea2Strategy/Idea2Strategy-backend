package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.strategy.StrategyCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = StrategyDocumentPersistenceIntegrationTest.TestApplication.class)
class StrategyDocumentPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T01:00:00Z");

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
    private StrategyJooqQueryAdapter strategyQueryAdapter;

    @Autowired
    private StrategyDocumentJpaCommandAdapter documentCommandAdapter;

    @Autowired
    private StrategyDocumentJooqQueryAdapter documentQueryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareOwner() {
        jdbcTemplate.update("delete from strategy.strategy_documents");
        jdbcTemplate.update("delete from strategy.strategies");
        jdbcTemplate.execute(
                "truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbcTemplate.update("delete from identity.accounts where id in (?, ?)", OWNER_ID, OTHER_OWNER_ID);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?), (?, 'ACTIVE', ?)",
                OWNER_ID,
                NOW.atOffset(ZoneOffset.UTC),
                OTHER_OWNER_ID,
                NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void jsonbDocumentsAndHashesRoundTripWithoutLeakingToAnotherOwner() {
        var owner = new TestPrincipal(OWNER_ID);
        new StrategyCommandService(
                        strategyCommandAdapter,
                        owner,
                        new FixedIdGenerator(STRATEGY_ID),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new RecordingDomainEventPublisher())
                .createBasic("Momentum", null);
        var commands = new StrategyDocumentCommandService(
                documentCommandAdapter,
                documentQueryAdapter,
                strategyQueryAdapter,
                owner,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var saved = commands.save(
                STRATEGY_ID,
                "{\"groups\":[{\"id\":\"buy\",\"blocks\":[{\"id\":\"rsi\",\"period\":14}]}],\"mode\":\"BASIC\"}",
                "{\"positions\":{\"rsi\":{\"x\":100,\"y\":220}},\"viewport\":{\"x\":12,\"y\":34}}",
                "basic-semantic/v1",
                "basic-presentation/v1");
        var loaded = new StrategyDocumentQueryService(documentQueryAdapter, owner).getOwned(STRATEGY_ID);

        assertThat(loaded).isEqualTo(saved);
        assertThat(jdbcTemplate.queryForObject(
                        "select jsonb_typeof(semantic_document) from strategy.strategy_documents where strategy_id = ?",
                        String.class,
                        STRATEGY_ID))
                .isEqualTo("object");
        assertThatThrownBy(() -> new StrategyDocumentQueryService(
                                documentQueryAdapter, new TestPrincipal(OTHER_OWNER_ID))
                        .getOwned(STRATEGY_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Strategy document not found");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {StrategyJpaEntity.class, StrategyDocumentJpaEntity.class})
    @EnableJpaRepositories(basePackageClasses = {
        StrategySpringDataRepository.class,
        StrategyDocumentSpringDataRepository.class
    })
    @Import({
        StrategyJpaCommandAdapter.class,
        StrategyJooqQueryAdapter.class,
        StrategyDocumentJpaCommandAdapter.class,
        StrategyDocumentJooqQueryAdapter.class
    })
    static class TestApplication {}
}
