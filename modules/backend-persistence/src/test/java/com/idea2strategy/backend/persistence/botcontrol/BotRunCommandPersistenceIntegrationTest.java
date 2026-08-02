package com.idea2strategy.backend.persistence.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatchMode;
import com.idea2strategy.backend.messaging.strategybot.v1.StrategyBotContractFixtures;
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
@SpringBootTest(classes = BotRunCommandPersistenceIntegrationTest.TestApplication.class)
class BotRunCommandPersistenceIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000022");
    private static final UUID PERSONAL_BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000022");
    private static final UUID WAITING_BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000022");
    private static final UUID ROOM_ID = UUID.fromString("40000000-0000-4000-8000-000000000022");
    private static final UUID PARTICIPATION_ID = UUID.fromString("50000000-0000-4000-8000-000000000022");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
    private static final Instant ROOM_START = Instant.parse("2026-08-03T13:30:00Z");
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

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
    private BotRunCommandJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void preparePersonalAndWaitingBots() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        insertBot(PERSONAL_BOT_ID, "Personal", HASH_A);
        insertBot(WAITING_BOT_ID, "Waiting", HASH_B);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'Room', 'PUBLIC', 'RECRUITING', ?)",
                ROOM_ID, OWNER_ID, at);
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'America/New_York')",
                ROOM_ID,
                at.minusDays(1),
                at.minusHours(1),
                ROOM_START.atOffset(ZoneOffset.UTC),
                ROOM_START.atOffset(ZoneOffset.UTC),
                ROOM_START.plusSeconds(86_400).atOffset(ZoneOffset.UTC),
                ROOM_START.plusSeconds(90_000).atOffset(ZoneOffset.UTC));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at) "
                        + "values (?, ?, ?, ?, 'waiting-bot', 'REGISTERED', ?)",
                PARTICIPATION_ID, ROOM_ID, WAITING_BOT_ID, OWNER_ID, at);
    }

    @Test
    void emitsOneStableImmediateOrWaitingCommandPerBot() throws Exception {
        var personalFirst = adapter.issueOwned(PERSONAL_BOT_ID, OWNER_ID, NOW).orElseThrow();
        var personalRetry = adapter.issueOwned(PERSONAL_BOT_ID, OWNER_ID, NOW.plusSeconds(30)).orElseThrow();
        var waitingFirst = adapter.issueOwned(WAITING_BOT_ID, OWNER_ID, NOW).orElseThrow();
        var waitingRetry = adapter.issueOwned(WAITING_BOT_ID, OWNER_ID, NOW.plusSeconds(30)).orElseThrow();

        assertThat(personalFirst.mode()).isEqualTo(BotRunDispatchMode.IMMEDIATE);
        assertThat(personalFirst.created()).isTrue();
        assertThat(personalRetry.created()).isFalse();
        assertThat(personalRetry.idempotencyKey()).isEqualTo(personalFirst.idempotencyKey());
        assertThat(waitingFirst.mode()).isEqualTo(BotRunDispatchMode.WAITING);
        assertThat(waitingFirst.executionEligibleFrom()).isEqualTo(ROOM_START);
        assertThat(waitingFirst.created()).isTrue();
        assertThat(waitingRetry.created()).isFalse();
        assertThat(waitingRetry.idempotencyKey()).isEqualTo(waitingFirst.idempotencyKey());
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages where event_type = 'BOT_RUN_COMMAND'",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'executionEligibleFrom' "
                                + "from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        WAITING_BOT_ID))
                .isEqualTo(ROOM_START.toString());
        var transported = OBJECT_MAPPER.readValue(
                jdbc.queryForObject(
                        "select payload_document::text from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        WAITING_BOT_ID),
                StrategyBotContractFixtures.BotRunCommand.class);
        assertThat(transported.metadata().contractVersion()).isEqualTo("strategy-bot.v1");
        assertThat(transported.metadata().messageType()).isEqualTo("BOT_RUN_COMMAND");
        assertThat(transported.metadata().messageId()).isEqualTo(waitingFirst.messageId().toString());
        assertThat(transported.metadata().idempotencyKey()).isEqualTo(waitingFirst.idempotencyKey());
        assertThat(transported.botId()).isEqualTo(WAITING_BOT_ID.toString());
        assertThat(transported.expectedSnapshotHash()).isEqualTo("sha256:" + HASH_B);
        assertThat(transported.executionEligibleFrom()).isEqualTo(ROOM_START.toString());
        assertThat(jdbc.queryForObject(
                        "select event_schema_version from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        WAITING_BOT_ID))
                .isEqualTo(transported.metadata().contractVersion());
        assertThat(jdbc.queryForObject(
                        "select event_type from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        WAITING_BOT_ID))
                .isEqualTo(transported.metadata().messageType());
        assertThat(jdbc.queryForObject(
                        "select id::text from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        WAITING_BOT_ID))
                .isEqualTo(transported.metadata().messageId());
        assertThat(jdbc.queryForObject(
                        "select idempotency_key from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        WAITING_BOT_ID))
                .isEqualTo(transported.metadata().idempotencyKey());
        assertThat("sha256:" + jdbc.queryForObject(
                        "select snapshot_hash from bot.launch_snapshots where bot_id = ?",
                        String.class,
                        WAITING_BOT_ID))
                .isEqualTo(transported.expectedSnapshotHash());
        assertThat(jdbc.queryForObject(
                        "select execution_eligible_from from bot.bots where id = ?",
                        java.time.OffsetDateTime.class,
                        WAITING_BOT_ID)
                .toInstant())
                .isEqualTo(ROOM_START);
        assertThat(jdbc.queryForObject(
                        "select due_at from bot.continuation_deadlines where bot_id = ?",
                        java.time.OffsetDateTime.class,
                        PERSONAL_BOT_ID)
                .toInstant())
                .isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(jdbc.queryForObject(
                        "select count(*) from bot.continuation_deadlines where bot_id = ?",
                        Integer.class,
                        WAITING_BOT_ID))
                .isZero();
        assertThat(adapter.issueOwned(PERSONAL_BOT_ID, UUID.randomUUID(), NOW)).isEmpty();
    }

    private void insertBot(UUID botId, String name, String snapshotHash) {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, 0, ?)",
                botId, OWNER_ID, name, at, at, at, at);
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                botId, HASH_A, HASH_B, snapshotHash, at);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BotRunCommandJooqAdapter.class)
    static class TestApplication {}
}
