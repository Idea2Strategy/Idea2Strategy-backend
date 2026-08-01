package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.competition.RoomScheduleTransitionReport;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
@SpringBootTest(classes = RoomScheduleTransitionPersistenceIntegrationTest.TestApplication.class)
class RoomScheduleTransitionPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000084");
    private static final UUID ROOM_ID = UUID.fromString("20000000-0000-4000-8000-000000000084");
    private static final Instant RECRUITMENT = Instant.parse("2026-08-02T01:00:00Z");
    private static final Instant EVALUATION = Instant.parse("2026-08-02T02:00:00Z");
    private static final Instant END = Instant.parse("2026-08-02T03:00:00Z");

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
    private RoomScheduleTransitionJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from competition.room_events");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from identity.accounts");
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
    }

    @Test
    void catchesUpEveryDueBoundaryInOrderAndIsIdempotent() {
        seedRoom("DRAFT");
        Instant observedAt = END.plusSeconds(30);

        assertThat(adapter.advanceDue(observedAt, 10))
                .isEqualTo(new RoomScheduleTransitionReport(observedAt, 1, 3));
        assertThat(adapter.advanceDue(observedAt, 10))
                .isEqualTo(new RoomScheduleTransitionReport(observedAt, 0, 0));

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.rooms where id = ?", String.class, ROOM_ID))
                .isEqualTo("ENDED");
        assertThat(jdbc.queryForObject(
                        "select ended_at from competition.rooms where id = ?",
                        java.time.OffsetDateTime.class,
                        ROOM_ID).toInstant())
                .isEqualTo(observedAt);
        assertThat(jdbc.queryForList(
                        "select event_sequence, event_type, resulting_status::text as status "
                                + "from competition.room_events where room_id = ? order by event_sequence",
                        ROOM_ID))
                .extracting(row -> row.get("event_type"))
                .containsExactly("RECRUITMENT_OPENED", "EVALUATION_STARTED", "EVALUATION_ENDED");
        assertThat(jdbc.queryForList(
                        "select payload_document ->> 'scheduledAt' as scheduled_at "
                                + "from competition.room_events where room_id = ? order by event_sequence",
                        ROOM_ID))
                .extracting(row -> row.get("scheduled_at"))
                .containsExactly(RECRUITMENT.toString(), EVALUATION.toString(), END.toString());
    }

    @Test
    void concurrentBatchInstancesApplyOneTransitionOnly() throws Exception {
        seedRoom("RECRUITING");
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RoomScheduleTransitionReport> first = executor.submit(() -> {
                gate.await();
                return adapter.advanceDue(EVALUATION, 10);
            });
            Future<RoomScheduleTransitionReport> second = executor.submit(() -> {
                gate.await();
                return adapter.advanceDue(EVALUATION, 10);
            });
            gate.countDown();
            List<RoomScheduleTransitionReport> reports = List.of(first.get(), second.get());
            assertThat(reports).extracting(RoomScheduleTransitionReport::transitionsApplied).containsExactlyInAnyOrder(1, 0);
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.room_events where room_id = ?", Integer.class, ROOM_ID))
                .isEqualTo(1);
    }

    private void seedRoom(String status) {
        var createdAt = RECRUITMENT.minusSeconds(3600).atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'Schedule room', 'PUBLIC', "
                        + "?::competition.room_status, ?::timestamptz)",
                ROOM_ID, OWNER_ID, status, createdAt);
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, "
                        + "?::timestamptz, ?::timestamptz, 'America/New_York')",
                ROOM_ID,
                RECRUITMENT.atOffset(ZoneOffset.UTC),
                RECRUITMENT.atOffset(ZoneOffset.UTC),
                EVALUATION.atOffset(ZoneOffset.UTC),
                EVALUATION.atOffset(ZoneOffset.UTC),
                END.atOffset(ZoneOffset.UTC),
                END.plusSeconds(3600).atOffset(ZoneOffset.UTC));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(RoomScheduleTransitionJooqAdapter.class)
    static class TestApplication {}
}
