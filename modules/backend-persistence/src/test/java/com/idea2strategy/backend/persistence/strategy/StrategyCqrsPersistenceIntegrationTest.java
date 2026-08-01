package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.StrategyCommandService;
import com.idea2strategy.backend.application.strategy.StrategyQueryService;
import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestPrincipal;
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
@SpringBootTest(classes = StrategyCqrsPersistenceIntegrationTest.TestApplication.class)
class StrategyCqrsPersistenceIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private StrategyJpaCommandAdapter commandAdapter;

    @Autowired
    private StrategyJooqQueryAdapter queryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareOwner() {
        jdbcTemplate.update("delete from strategy.strategies");
        jdbcTemplate.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) "
                        + "values (?, 'ACTIVE', ?)",
                OWNER_ID,
                NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void savedBasicStrategyCanBeQueriedByTheSameOwner() {
        var principal = new TestPrincipal(OWNER_ID);
        var events = new RecordingDomainEventPublisher();
        var commandService = new StrategyCommandService(
                commandAdapter,
                principal,
                new FixedIdGenerator(STRATEGY_ID),
                Clock.fixed(NOW, ZoneOffset.UTC),
                events);
        var queryService = new StrategyQueryService(queryAdapter, principal);

        var createdId = commandService.createBasic("Momentum", "Minimal Basic strategy");
        var loaded = queryService.getOwned(createdId);

        assertThat(createdId).isEqualTo(STRATEGY_ID);
        assertThat(loaded.id()).isEqualTo(STRATEGY_ID);
        assertThat(loaded.ownerAccountId()).isEqualTo(OWNER_ID);
        assertThat(loaded.mode()).isEqualTo(StrategyMode.BASIC);
        assertThat(events.publishedEvents()).hasSize(1);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = StrategyJpaEntity.class)
    @EnableJpaRepositories(basePackageClasses = StrategySpringDataRepository.class)
    @Import({StrategyJpaCommandAdapter.class, StrategyJooqQueryAdapter.class})
    static class TestApplication {}
}
