package com.idea2strategy.backend.persistence.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.botcontrol.BotCommandService;
import com.idea2strategy.backend.application.botcontrol.BotQueryService;
import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.MutableClock;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
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
@SpringBootTest(classes = BotControlCqrsPersistenceIntegrationTest.TestApplication.class)
class BotControlCqrsPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant STARTED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant STOP_REQUESTED_AT = Instant.parse("2026-08-01T01:00:00Z");

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
    private BotJpaCommandAdapter commandAdapter;

    @Autowired
    private BotJooqQueryAdapter queryAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareOwner() {
        jdbcTemplate.update("delete from bot.bots");
        jdbcTemplate.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbcTemplate.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) "
                        + "values (?, 'ACTIVE', ?)",
                OWNER_ID,
                STARTED_AT.atOffset(ZoneOffset.UTC));
    }

    @Test
    void startedBotCanBeStoppedThroughJpaCommandsAndJooqQueries() {
        var principal = new TestPrincipal(OWNER_ID);
        var clock = new MutableClock(STARTED_AT, ZoneOffset.UTC);
        var events = new RecordingDomainEventPublisher();
        var commands = new BotCommandService(
                commandAdapter,
                queryAdapter,
                principal,
                new FixedIdGenerator(BOT_ID),
                clock,
                events);
        var queries = new BotQueryService(queryAdapter, principal);

        UUID id = commands.startBasic("Basic bot");
        clock.advanceTo(STOP_REQUESTED_AT);
        commands.requestStop(id, "USER_REQUESTED");
        var loaded = queries.getOwned(id);

        assertThat(loaded.lifecycleStatus()).isEqualTo(BotLifecycleStatus.STOPPING);
        assertThat(loaded.stopRequestedAt()).isEqualTo(STOP_REQUESTED_AT);
        assertThat(events.publishedEvents()).hasSize(2);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BotJpaEntity.class)
    @EnableJpaRepositories(basePackageClasses = BotSpringDataRepository.class)
    @Import({BotJpaCommandAdapter.class, BotJooqQueryAdapter.class})
    static class TestApplication {}
}
