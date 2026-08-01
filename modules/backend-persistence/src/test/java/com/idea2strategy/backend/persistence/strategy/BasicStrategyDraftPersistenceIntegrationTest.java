package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import com.idea2strategy.backend.application.strategy.StrategyDraftConflictException;
import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.strategy.StrategyCreated;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BasicStrategyDraftPersistenceIntegrationTest.TestApplication.class)
class BasicStrategyDraftPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T03:00:00Z");

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
    void prepareOwner() {
        jdbcTemplate.update("delete from strategy.strategy_documents");
        jdbcTemplate.update("delete from strategy.strategies");
        jdbcTemplate.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                OWNER_ID,
                NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void createsDraftRowsAndRejectsAStaleConditionalUpdate() {
        var principal = new TestPrincipal(OWNER_ID);
        var events = new RecordingDomainEventPublisher();
        var service = new BasicStrategyDraftCommandService(
                draftCommandAdapter,
                strategyQueryAdapter,
                documentQueryAdapter,
                principal,
                new FixedIdGenerator(STRATEGY_ID),
                Clock.fixed(NOW, ZoneOffset.UTC),
                events);

        UUID strategyId = service.createBasic("Momentum", null);
        var latest = service.autosave(
                strategyId,
                0,
                "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"latest\"}]}",
                "{\"positions\":{},\"viewport\":{\"x\":10,\"y\":20,\"zoom\":1}}",
                "basic-semantic/v1",
                "basic-presentation/v1");

        assertThatThrownBy(() -> service.saveExplicitly(
                        strategyId,
                        0,
                        "{\"mode\":\"BASIC\",\"groups\":[{\"id\":\"stale\"}]}",
                        "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}",
                        "basic-semantic/v1",
                        "basic-presentation/v1"))
                .isInstanceOf(StrategyDraftConflictException.class);

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from strategy.strategies where id = ?", Integer.class, STRATEGY_ID))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from strategy.strategy_documents where strategy_id = ?",
                        Integer.class,
                        STRATEGY_ID))
                .isEqualTo(1);
        assertThat(new StrategyDocumentQueryService(documentQueryAdapter, principal).getOwned(strategyId))
                .isEqualTo(latest);
        assertThat(draftCommandAdapter.replaceDocument(latest, 0)).isFalse();
        assertThat(events.publishedEvents()).containsExactly(
                new StrategyCreated(STRATEGY_ID, OWNER_ID, StrategyMode.BASIC, NOW));
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
