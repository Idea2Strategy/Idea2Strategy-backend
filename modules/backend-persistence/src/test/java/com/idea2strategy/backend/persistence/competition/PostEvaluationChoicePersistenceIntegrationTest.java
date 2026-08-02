package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.PostEvaluationAction;
import com.idea2strategy.backend.application.competition.PostEvaluationChoiceAccessException;
import com.idea2strategy.backend.application.competition.PostEvaluationChoiceConflictException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
@SpringBootTest(classes = PostEvaluationChoicePersistenceIntegrationTest.TestApplication.class)
class PostEvaluationChoicePersistenceIntegrationTest {
    private static final UUID OWNER_ID = id(1);
    private static final UUID OTHER_OWNER_ID = id(2);
    private static final UUID ROOM_ID = id(3);
    private static final UUID BOT_ID = id(4);
    private static final UUID PARTICIPATION_ID = id(5);
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

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

    @Autowired PostEvaluationChoiceJooqAdapter adapter;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.launch_snapshots");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from identity.accounts where id in (?, ?)", OWNER_ID, OTHER_OWNER_ID);
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE'), (?, 'ACTIVE')",
                OWNER_ID, OTHER_OWNER_ID);
        seedEvaluation();
    }

    @Test
    void preservesUndecidedAndRecordsExplicitChangesWithAuditEvidence() {
        assertThat(adapter.findOwned(ROOM_ID, PARTICIPATION_ID, OWNER_ID).action()).isNull();

        var continued = adapter.updateOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, PostEvaluationAction.CONTINUE_PRIVATE, NOW);
        assertThat(continued.action()).isEqualTo(PostEvaluationAction.CONTINUE_PRIVATE);
        assertThat(value("select post_room_action::text from competition.participations where id = ?"))
                .isEqualTo("CONTINUE_PRIVATE");
        assertThat(value(
                        "select payload_document->>'actorAccountId' from competition.participation_events "
                                + "where participation_id = ? and event_sequence = 1"))
                .isEqualTo(OWNER_ID.toString());

        adapter.updateOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, PostEvaluationAction.STOP_AFTER_EVALUATION, NOW.plusSeconds(1));
        assertThat(value("select post_room_action::text from competition.participations where id = ?"))
                .isEqualTo("STOP");
        assertThat(value(
                        "select payload_document->>'previousAction' from competition.participation_events "
                                + "where participation_id = ? and event_sequence = 2"))
                .isEqualTo("CONTINUE_PRIVATE");
        assertThat(adapter.findOwned(ROOM_ID, PARTICIPATION_ID, OWNER_ID).action())
                .isEqualTo(PostEvaluationAction.STOP_AFTER_EVALUATION);
    }

    @Test
    void makesAnIdenticalRetryIdempotent() {
        var first = adapter.updateOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, PostEvaluationAction.CONTINUE_PRIVATE, NOW);
        var retried = adapter.updateOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, PostEvaluationAction.CONTINUE_PRIVATE, NOW.plusSeconds(1));

        assertThat(retried.recordedAt()).isEqualTo(first.recordedAt());
        assertThat(count("select count(*) from competition.participation_events where participation_id = ?"))
                .isEqualTo(1);
    }

    @Test
    void rejectsAnotherOwnerAndPostDeadlineOrLockedMutations() {
        assertThatThrownBy(() -> adapter.updateOwned(
                        ROOM_ID, PARTICIPATION_ID, OTHER_OWNER_ID,
                        PostEvaluationAction.CONTINUE_PRIVATE, NOW))
                .isInstanceOf(PostEvaluationChoiceAccessException.class);
        assertThatThrownBy(() -> adapter.updateOwned(
                        ROOM_ID, PARTICIPATION_ID, OWNER_ID,
                        PostEvaluationAction.CONTINUE_PRIVATE, NOW.plusSeconds(3600)))
                .isInstanceOf(PostEvaluationChoiceConflictException.class);

        jdbc.update(
                "update competition.participations set action_locked_at = ? where id = ?",
                utc(NOW), PARTICIPATION_ID);
        assertThatThrownBy(() -> adapter.updateOwned(
                        ROOM_ID, PARTICIPATION_ID, OWNER_ID,
                        PostEvaluationAction.CONTINUE_PRIVATE, NOW.plusSeconds(1)))
                .isInstanceOf(PostEvaluationChoiceConflictException.class)
                .hasMessageContaining("locked");
    }

    @Test
    void serializesConcurrentDifferentChoicesWithoutLosingAuditEvents() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var continued = executor.submit(() -> adapter.updateOwned(
                    ROOM_ID, PARTICIPATION_ID, OWNER_ID, PostEvaluationAction.CONTINUE_PRIVATE, NOW));
            var stopped = executor.submit(() -> adapter.updateOwned(
                    ROOM_ID, PARTICIPATION_ID, OWNER_ID, PostEvaluationAction.STOP_AFTER_EVALUATION, NOW.plusMillis(1)));
            continued.get(30, TimeUnit.SECONDS);
            stopped.get(30, TimeUnit.SECONDS);
        }

        assertThat(count("select count(*) from competition.participation_events where participation_id = ?"))
                .isEqualTo(2);
        assertThat(count(
                        "select count(distinct event_sequence) from competition.participation_events "
                                + "where participation_id = ?"))
                .isEqualTo(2);
    }

    private void seedEvaluation() {
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'E25 Room', 'PUBLIC', 'EVALUATING', ?)",
                ROOM_ID, OWNER_ID, utc(NOW.minusSeconds(7200)));
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                ROOM_ID, utc(NOW.minusSeconds(7200)), utc(NOW.minusSeconds(5400)), utc(NOW.minusSeconds(3600)),
                utc(NOW.minusSeconds(3600)), utc(NOW.plusSeconds(3600)), utc(NOW.plusSeconds(7200)));
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'E25 Bot', 'RUNNING', ?, ?, ?, 0, ?)",
                BOT_ID, OWNER_ID, utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(3600)),
                utc(NOW.plusSeconds(3600)), utc(NOW.minusSeconds(3600)));
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, 'semantic-e25', "
                        + "'presentation-e25', 'snapshot-e25', ?)",
                BOT_ID, utc(NOW.minusSeconds(3600)));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, evaluation_started_at) "
                        + "values (?, ?, ?, ?, 'alias-e25', 'EVALUATING', ?, ?)",
                PARTICIPATION_ID, ROOM_ID, BOT_ID, OWNER_ID, utc(NOW.minusSeconds(5400)), utc(NOW.minusSeconds(3600)));
    }

    private String value(String sql) {
        return jdbc.queryForObject(sql, String.class, PARTICIPATION_ID);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, PARTICIPATION_ID);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a5000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(PostEvaluationChoiceJooqAdapter.class)
    static class TestApplication {}
}
