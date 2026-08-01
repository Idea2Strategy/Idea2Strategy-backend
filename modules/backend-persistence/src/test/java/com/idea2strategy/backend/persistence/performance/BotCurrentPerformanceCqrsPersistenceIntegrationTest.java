package com.idea2strategy.backend.persistence.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
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
@SpringBootTest(classes = BotCurrentPerformanceCqrsPersistenceIntegrationTest.TestApplication.class)
class BotCurrentPerformanceCqrsPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-01T01:00:00Z");

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
    private BotCurrentPerformanceJpaCommandAdapter commandAdapter;

    @Autowired
    private BotCurrentPerformanceJooqQueryAdapter queryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareBot() {
        jdbcTemplate.update("delete from performance.bot_current_projections where bot_id = ?", BOT_ID);
        jdbcTemplate.update("delete from bot.bots where id = ?", BOT_ID);
        jdbcTemplate.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                OWNER_ID,
                UPDATED_AT.atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, execution_eligible_from, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', 'Performance bot', 'RUNNING', ?, ?, ?, ?)",
                BOT_ID,
                OWNER_ID,
                UPDATED_AT.atOffset(ZoneOffset.UTC),
                UPDATED_AT.atOffset(ZoneOffset.UTC),
                UPDATED_AT.atOffset(ZoneOffset.UTC),
                UPDATED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void savedCurrentPerformanceCanBeReadThroughJooq() {
        var performance = new BotCurrentPerformance(
                BOT_ID,
                new BigDecimal("104250.00000000"),
                new BigDecimal("4.25000000"),
                new BigDecimal("1.50000000"),
                new BigDecimal("1.20000000"),
                "{\"cashAmount\":\"25000.00000000\"}",
                "ledger-state-v1",
                "position-state-v1",
                "performance-v1",
                42,
                "projection-v1",
                UPDATED_AT);

        commandAdapter.save(performance);
        var loaded = queryAdapter.findByBotId(BOT_ID).orElseThrow();

        assertThat(loaded.equityAmount()).isEqualByComparingTo("104250.00000000");
        assertThat(loaded.totalReturnPct()).isEqualByComparingTo("4.25000000");
        assertThat(loaded.maxDrawdownPct()).isEqualByComparingTo("1.50000000");
        assertThat(loaded.metricsDocument()).contains("cashAmount");
        assertThat(loaded.lastEventSequence()).isEqualTo(42);
        assertThat(loaded.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BotCurrentPerformanceJpaEntity.class)
    @EnableJpaRepositories(basePackageClasses = BotCurrentPerformanceSpringDataRepository.class)
    @Import({BotCurrentPerformanceJpaCommandAdapter.class, BotCurrentPerformanceJooqQueryAdapter.class})
    static class TestApplication {}
}
