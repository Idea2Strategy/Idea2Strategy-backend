package com.idea2strategy.backend.persistence.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
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
@SpringBootTest(classes = BotStopCommandPersistenceIntegrationTest.TestApplication.class)
class BotStopCommandPersistenceIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000024");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000024");
    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final String SNAPSHOT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

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
    private BotStopCommandJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareRunningBot() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, started_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'Stopping bot', 'RUNNING', ?, ?, ?, ?, 0, ?)",
                BOT_ID, OWNER_ID, at, at, at, at, at);
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                BOT_ID, SNAPSHOT_HASH, SNAPSHOT_HASH, SNAPSHOT_HASH, at);
        jdbc.update(
                "insert into operations.outbox_messages "
                        + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, event_schema_version, "
                        + "payload_document, idempotency_key, created_at, published_at) "
                        + "values (?, 'strategy-bot', ?, 4, 'BOT_RUN_COMMAND', 'strategy-bot.v1', '{}'::jsonb, ?, ?, ?)",
                UUID.randomUUID(),
                BOT_ID,
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                at,
                at);
    }

    @Test
    void atomicallyBlocksEvaluationAndEmitsOneRetrySafeStopCommand() throws Exception {
        var first = adapter.issueOwned(BOT_ID, OWNER_ID, "USER_REQUESTED", NOW).orElseThrow();
        jdbc.update(
                "update operations.outbox_messages set publish_attempt_count = 1, last_failure_code = 'BROKER_UNAVAILABLE' "
                        + "where id = ?",
                first.messageId());
        var retry = adapter.issueOwned(BOT_ID, OWNER_ID, "USER_REQUESTED", NOW.plusSeconds(30)).orElseThrow();

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.messageId()).isEqualTo(first.messageId());
        assertThat(retry.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(retry.lifecycleStatus()).isEqualTo(BotLifecycleStatus.STOPPING);
        assertThat(jdbc.queryForObject(
                        "select lifecycle_status::text from bot.bots where id = ?", String.class, BOT_ID))
                .isEqualTo("STOPPING");
        assertThat(jdbc.queryForObject(
                        "select execution_block_reason_code from bot.bots where id = ?", String.class, BOT_ID))
                .isEqualTo("BOT_STOP_REQUESTED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages where event_type = 'BOT_STOP_COMMAND'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'reasonCode' from operations.outbox_messages where id = ?",
                        String.class,
                        first.messageId()))
                .isEqualTo("USER_REQUESTED");
        var transported = OBJECT_MAPPER.readValue(
                jdbc.queryForObject(
                        "select payload_document::text from operations.outbox_messages where id = ?",
                        String.class,
                        first.messageId()),
                StrategyBotContractFixtures.BotStopCommand.class);
        assertThat(transported.metadata().contractVersion()).isEqualTo("strategy-bot.v1");
        assertThat(transported.metadata().messageType()).isEqualTo("BOT_STOP_COMMAND");
        assertThat(transported.metadata().messageId()).isEqualTo(first.messageId().toString());
        assertThat(transported.metadata().idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(transported.botId()).isEqualTo(BOT_ID.toString());
        assertThat(transported.expectedSnapshotHash()).isEqualTo("sha256:" + SNAPSHOT_HASH);
        assertThat(transported.reasonCode()).isEqualTo("USER_REQUESTED");
        assertThat(jdbc.queryForObject(
                        "select event_schema_version from operations.outbox_messages where id = ?",
                        String.class,
                        first.messageId()))
                .isEqualTo(transported.metadata().contractVersion());
        assertThat(jdbc.queryForObject(
                        "select event_type from operations.outbox_messages where id = ?",
                        String.class,
                        first.messageId()))
                .isEqualTo(transported.metadata().messageType());
        assertThat(jdbc.queryForObject(
                        "select id::text from operations.outbox_messages where id = ?",
                        String.class,
                        first.messageId()))
                .isEqualTo(transported.metadata().messageId());
        assertThat(jdbc.queryForObject(
                        "select idempotency_key from operations.outbox_messages where id = ?",
                        String.class,
                        first.messageId()))
                .isEqualTo(transported.metadata().idempotencyKey());
        assertThat("sha256:" + jdbc.queryForObject(
                        "select snapshot_hash from bot.launch_snapshots where bot_id = ?",
                        String.class,
                        BOT_ID))
                .isEqualTo(transported.expectedSnapshotHash());
        assertThat(jdbc.queryForObject(
                        "select aggregate_sequence from operations.outbox_messages where id = ?",
                        Long.class,
                        first.messageId()))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                        "select publish_attempt_count from operations.outbox_messages where id = ?",
                        Integer.class,
                        first.messageId()))
                .isEqualTo(1);
        assertThat(adapter.issueOwned(BOT_ID, UUID.randomUUID(), "USER_REQUESTED", NOW)).isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BotStopCommandJooqAdapter.class)
    static class TestApplication {}
}
