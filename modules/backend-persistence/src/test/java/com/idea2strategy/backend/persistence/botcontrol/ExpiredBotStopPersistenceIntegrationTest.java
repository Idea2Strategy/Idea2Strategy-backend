package com.idea2strategy.backend.persistence.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
@SpringBootTest(classes = ExpiredBotStopPersistenceIntegrationTest.TestApplication.class)
class ExpiredBotStopPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000026");
    private static final UUID EXPIRED_BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000026");
    private static final UUID FUTURE_BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000126");
    private static final UUID AFFILIATED_BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000226");
    private static final UUID ROOM_ID = UUID.fromString("30000000-0000-4000-8000-000000000026");
    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final String SNAPSHOT_HASH = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

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
    private ExpiredBotStopJooqQueryAdapter queryAdapter;

    @Autowired
    private BotStopCommandJooqAdapter commandAdapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareBots() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        insertBot(EXPIRED_BOT_ID, "Expired private bot", at);
        insertBot(FUTURE_BOT_ID, "Future private bot", at);
        insertBot(AFFILIATED_BOT_ID, "Affiliated bot", at);
        insertDeadline(EXPIRED_BOT_ID, NOW.minusSeconds(1), at);
        insertDeadline(FUTURE_BOT_ID, NOW.plusSeconds(1), at);
        insertDeadline(AFFILIATED_BOT_ID, NOW.minusSeconds(1), at);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'Room', 'PUBLIC', 'RECRUITING', ?)",
                ROOM_ID,
                OWNER_ID,
                at);
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at) "
                        + "values (?, ?, ?, ?, 'affiliated-bot', 'REGISTERED', ?)",
                UUID.randomUUID(),
                ROOM_ID,
                AFFILIATED_BOT_ID,
                OWNER_ID,
                at);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from operations.outbox_messages where aggregate_id in (?, ?, ?)",
                EXPIRED_BOT_ID, FUTURE_BOT_ID, AFFILIATED_BOT_ID);
        jdbc.update("delete from competition.participations where bot_id in (?, ?, ?)",
                EXPIRED_BOT_ID, FUTURE_BOT_ID, AFFILIATED_BOT_ID);
        jdbc.update("delete from bot.continuation_deadlines where bot_id in (?, ?, ?)",
                EXPIRED_BOT_ID, FUTURE_BOT_ID, AFFILIATED_BOT_ID);
        jdbc.update("delete from bot.launch_snapshots where bot_id in (?, ?, ?)",
                EXPIRED_BOT_ID, FUTURE_BOT_ID, AFFILIATED_BOT_ID);
        jdbc.update("delete from bot.bots where id in (?, ?, ?)",
                EXPIRED_BOT_ID, FUTURE_BOT_ID, AFFILIATED_BOT_ID);
        jdbc.update("delete from competition.rooms where id = ?", ROOM_ID);
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);
    }

    @Test
    void selectsOnlyExpiredUnaffiliatedRunningBotsAndStopsThemOnceAfterRevalidation() {
        var candidates = queryAdapter.findExpired(NOW, 100);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.botId()).isEqualTo(EXPIRED_BOT_ID);
            assertThat(candidate.ownerAccountId()).isEqualTo(OWNER_ID);
        });
        var candidate = candidates.getFirst();

        jdbc.update(
                "update bot.continuation_deadlines set due_at = ? where bot_id = ?",
                NOW.plusSeconds(60).atOffset(ZoneOffset.UTC),
                EXPIRED_BOT_ID);
        assertThat(commandAdapter.issueExpired(candidate, NOW)).isFalse();

        jdbc.update(
                "update bot.continuation_deadlines set due_at = ? where bot_id = ?",
                NOW.minusSeconds(1).atOffset(ZoneOffset.UTC),
                EXPIRED_BOT_ID);
        assertThat(commandAdapter.issueExpired(candidate, NOW)).isTrue();
        assertThat(commandAdapter.issueExpired(candidate, NOW.plusSeconds(30))).isFalse();

        assertThat(jdbc.queryForObject(
                        "select lifecycle_status::text from bot.bots where id = ?", String.class, EXPIRED_BOT_ID))
                .isEqualTo("STOPPING");
        assertThat(jdbc.queryForObject(
                        "select stop_reason_code from bot.bots where id = ?", String.class, EXPIRED_BOT_ID))
                .isEqualTo("CONTINUATION_DEADLINE_EXPIRED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages "
                                + "where aggregate_id = ? and event_type = 'BOT_STOP_COMMAND'",
                        Integer.class,
                        EXPIRED_BOT_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'reasonCode' from operations.outbox_messages "
                                + "where aggregate_id = ? and event_type = 'BOT_STOP_COMMAND'",
                        String.class,
                        EXPIRED_BOT_ID))
                .isEqualTo("CONTINUATION_DEADLINE_EXPIRED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages where aggregate_id in (?, ?)",
                        Integer.class,
                        FUTURE_BOT_ID,
                        AFFILIATED_BOT_ID))
                .isZero();
    }

    private void insertBot(UUID botId, String name, java.time.OffsetDateTime at) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, started_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, ?, 0, ?)",
                botId, OWNER_ID, name, at, at, at, at, at);
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                botId, SNAPSHOT_HASH, SNAPSHOT_HASH, SNAPSHOT_HASH, at);
    }

    private void insertDeadline(UUID botId, Instant dueAt, java.time.OffsetDateTime at) {
        jdbc.update(
                "insert into bot.continuation_deadlines "
                        + "(bot_id, due_at, renewal_sequence, created_at, updated_at) values (?, ?, 0, ?, ?)",
                botId, dueAt.atOffset(ZoneOffset.UTC), at, at);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ExpiredBotStopJooqQueryAdapter.class, BotStopCommandJooqAdapter.class})
    static class TestApplication {}
}
