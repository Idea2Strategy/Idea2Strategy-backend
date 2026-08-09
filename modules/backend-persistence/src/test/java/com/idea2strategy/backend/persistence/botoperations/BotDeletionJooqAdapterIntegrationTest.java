package com.idea2strategy.backend.persistence.botoperations;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.botoperations.BotDeletionResult;
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
@SpringBootTest(classes = BotDeletionJooqAdapterIntegrationTest.TestApplication.class)
class BotDeletionJooqAdapterIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID STOPPED_BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID RUNNING_BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private BotDeletionJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedBotsAndEvidence() {
        jdbc.update("delete from bot.bot_events where bot_id in (?, ?)", STOPPED_BOT_ID, RUNNING_BOT_ID);
        jdbc.update("delete from bot.bots where id in (?, ?)", STOPPED_BOT_ID, RUNNING_BOT_ID);
        jdbc.execute("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id in (?, ?)", OWNER_ID, OTHER_OWNER_ID);
        var now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?), (?, 'ACTIVE', ?)",
                OWNER_ID, now, OTHER_OWNER_ID, now);
        jdbc.update(
                "insert into bot.bots (id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, stopped_at, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', 'Stopped bot', 'STOPPED', ?, ?, ?, ?, ?), "
                        + "(?, ?, 'BASIC', 'Running bot', 'RUNNING', ?, ?, null, ?, ?)",
                STOPPED_BOT_ID, OWNER_ID, now.minusSeconds(10), now.minusSeconds(30), now.minusSeconds(10), now.minusSeconds(30), now.minusSeconds(10),
                RUNNING_BOT_ID, OWNER_ID, now.minusSeconds(10), now.minusSeconds(30), now.minusSeconds(30), now.minusSeconds(10));
        jdbc.update(
                "insert into bot.bot_events (id, bot_id, event_sequence, event_type, event_schema_version, correlation_id, "
                        + "idempotency_key, occurred_at, received_at, summary_document) "
                        + "values (?, ?, 1, 'BOT_STOPPED', '1', ?, ?, ?, ?, '{}'::jsonb)",
                UUID.randomUUID(), STOPPED_BOT_ID, UUID.randomUUID(), "deletion-evidence", now.minusSeconds(10), now.minusSeconds(10));
    }

    @Test
    void softDeletesOnlyAStoppedBotAndPreservesItsEvidence() {
        assertThat(adapter.deleteOwnedStopped(STOPPED_BOT_ID, OWNER_ID, NOW))
                .isEqualTo(BotDeletionResult.DELETED);
        assertThat(adapter.deleteOwnedStopped(STOPPED_BOT_ID, OWNER_ID, NOW.plusSeconds(1)))
                .isEqualTo(BotDeletionResult.ALREADY_DELETED);
        assertThat(adapter.deleteOwnedStopped(RUNNING_BOT_ID, OWNER_ID, NOW))
                .isEqualTo(BotDeletionResult.NOT_STOPPED);

        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from bot.bots where id = ?", Boolean.class, STOPPED_BOT_ID))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from bot.bot_events where bot_id = ?", Long.class, STOPPED_BOT_ID))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select deleted_at is null from bot.bots where id = ?", Boolean.class, RUNNING_BOT_ID))
                .isTrue();
    }

    @Test
    void doesNotRevealAnotherOwnersBot() {
        assertThat(adapter.deleteOwnedStopped(STOPPED_BOT_ID, OTHER_OWNER_ID, NOW))
                .isEqualTo(BotDeletionResult.NOT_FOUND);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BotDeletionJooqAdapter.class)
    static class TestApplication {}
}
