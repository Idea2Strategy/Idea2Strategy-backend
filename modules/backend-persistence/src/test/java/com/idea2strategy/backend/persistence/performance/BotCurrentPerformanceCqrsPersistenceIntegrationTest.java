package com.idea2strategy.backend.persistence.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.performance.BotCurrentPerformanceCommandService;
import com.idea2strategy.backend.application.performance.EquityObservation;
import com.idea2strategy.backend.application.performance.LivePerformanceProjectionInput;
import com.idea2strategy.backend.application.performance.LivePerformanceSource;
import com.idea2strategy.backend.application.performance.ProjectionWriteDecision;
import com.idea2strategy.backend.domain.competition.CompetitionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.UUID;
import org.jooq.DSLContext;
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

    @Autowired
    private DSLContext dsl;

    private BotCurrentPerformanceCommandService commandService;

    @BeforeEach
    void prepareBot() {
        commandService = new BotCurrentPerformanceCommandService(commandAdapter);
        jdbcTemplate.update("delete from performance.bot_current_projections where bot_id = ?", BOT_ID);
        jdbcTemplate.update("delete from bot.bots where id = ?", BOT_ID);
        jdbcTemplate.execute("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
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
        assertThat(commandService.project(input(42, "104250.00000000", UPDATED_AT)))
                .isEqualTo(ProjectionWriteDecision.APPLIED);
        var loaded = queryAdapter.findByBotId(BOT_ID).orElseThrow();

        assertThat(loaded.equityAmount()).isEqualByComparingTo("104250.00000000");
        assertThat(loaded.totalReturnPct()).isEqualByComparingTo("4.25000000");
        assertThat(loaded.maxDrawdownPct()).isEqualByComparingTo("0.00000000");
        assertThat(loaded.metricsDocument()).contains("tradeCount");
        assertThat(loaded.lastEventSequence()).isEqualTo(42);
        assertThat(loaded.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void duplicateAndOutOfOrderEventsCannotOverwriteLatestProjectionAfterRestart() {
        var sequence42 = input(42, "104250.00000000", UPDATED_AT);
        var duplicate42 = input(42, "999999.00000000", UPDATED_AT.plusSeconds(1));
        var stale41 = input(41, "25001.00000000", UPDATED_AT.plusSeconds(2));

        assertThat(commandService.project(sequence42)).isEqualTo(ProjectionWriteDecision.APPLIED);

        var restartedService = new BotCurrentPerformanceCommandService(
                new BotCurrentPerformanceJpaCommandAdapter(dsl));
        assertThat(restartedService.project(duplicate42))
                .isEqualTo(ProjectionWriteDecision.IGNORED_STALE_OR_DUPLICATE);
        assertThat(restartedService.project(stale41))
                .isEqualTo(ProjectionWriteDecision.IGNORED_STALE_OR_DUPLICATE);

        var loaded = queryAdapter.findByBotId(BOT_ID).orElseThrow();
        assertThat(loaded.lastEventSequence()).isEqualTo(42);
        assertThat(loaded.equityAmount()).isEqualByComparingTo("104250.00000000");
        assertThat(loaded.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void concurrentWritersAlwaysLeaveTheHighestEventSequence() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var lower = executor.submit(() -> saveAfterBarrier(
                    input(50, "105000.00000000", UPDATED_AT.plusSeconds(50)), ready, start));
            var higher = executor.submit(() -> saveAfterBarrier(
                    input(51, "105100.00000000", UPDATED_AT.plusSeconds(51)), ready, start));

            ready.await();
            start.countDown();
            lower.get();
            higher.get();
        }

        var loaded = queryAdapter.findByBotId(BOT_ID).orElseThrow();
        assertThat(loaded.lastEventSequence()).isEqualTo(51);
        assertThat(loaded.equityAmount()).isEqualByComparingTo("105100.00000000");
    }

    private ProjectionWriteDecision saveAfterBarrier(
            LivePerformanceProjectionInput input,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return commandService.project(input);
    }

    private LivePerformanceProjectionInput input(long sequence, String equity, Instant updatedAt) {
        BigDecimal equityAmount = new BigDecimal(equity);
        return new LivePerformanceProjectionInput(
                BOT_ID,
                CompetitionType.LIVE_PAPER,
                LivePerformanceSource.LIVE_TRADING,
                new BigDecimal("100000.00000000"),
                new BigDecimal("25000.00000000"),
                List.of(equityAmount.subtract(new BigDecimal("25000.00000000"))),
                List.of(new EquityObservation(sequence, equityAmount)),
                new BigDecimal("1.20000000"),
                Map.of("tradeCount", sequence),
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64),
                "performance-v1",
                sequence,
                updatedAt);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BotCurrentPerformanceJpaEntity.class)
    @EnableJpaRepositories(basePackageClasses = BotCurrentPerformanceSpringDataRepository.class)
    @Import({BotCurrentPerformanceJpaCommandAdapter.class, BotCurrentPerformanceJooqQueryAdapter.class})
    static class TestApplication {}
}
