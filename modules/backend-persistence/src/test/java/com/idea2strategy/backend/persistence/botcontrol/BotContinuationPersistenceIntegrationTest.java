package com.idea2strategy.backend.persistence.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.botcontrol.BotContinuationConflictException;
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
@SpringBootTest(classes = BotContinuationPersistenceIntegrationTest.TestApplication.class)
class BotContinuationPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000025");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000025");
    private static final UUID ROOM_ID = UUID.fromString("30000000-0000-4000-8000-000000000025");
    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final Instant CURRENT_DUE = Instant.parse("2026-08-08T09:00:00Z");

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
    private BotContinuationJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareUnaffiliatedRunningBot() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, started_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'Private bot', 'RUNNING', ?, ?, ?, ?, 0, ?)",
                BOT_ID, OWNER_ID, at, at, at, at, at);
        jdbc.update(
                "insert into bot.continuation_deadlines "
                        + "(bot_id, due_at, renewal_sequence, created_at, updated_at) values (?, ?, 0, ?, ?)",
                BOT_ID, CURRENT_DUE.atOffset(ZoneOffset.UTC), at, at);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from operations.audit_events where target_id = ?", BOT_ID);
        jdbc.update("delete from competition.participations where bot_id = ?", BOT_ID);
        jdbc.update("delete from bot.continuation_deadlines where bot_id = ?", BOT_ID);
        jdbc.update("delete from bot.bots where id = ?", BOT_ID);
        jdbc.update("delete from competition.rooms where id = ?", ROOM_ID);
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);
    }

    @Test
    void readingDoesNotExtendAndExplicitRenewalUsesTheServerReceiptTime() {
        var before = adapter.findOwned(BOT_ID, OWNER_ID).orElseThrow();
        var renewed = adapter.renewOwned(BOT_ID, OWNER_ID, NOW).orElseThrow();

        assertThat(before.dueAt()).isEqualTo(CURRENT_DUE);
        assertThat(before.lastRenewedAt()).isNull();
        assertThat(renewed.dueAt()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(renewed.lastRenewedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject(
                        "select renewal_sequence from bot.continuation_deadlines where bot_id = ?",
                        Long.class,
                        BOT_ID))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.audit_events "
                                + "where target_id = ? and action_type = 'BOT_CONTINUATION_RENEWED'",
                        Integer.class,
                        BOT_ID))
                .isEqualTo(1);
        assertThat(adapter.findOwned(BOT_ID, UUID.randomUUID())).isEmpty();
    }

    @Test
    void rejectsEarlyRepeatedAndExpiredRenewals() {
        assertThatThrownBy(() -> adapter.renewOwned(BOT_ID, OWNER_ID, NOW.minusSeconds(2L * 24 * 60 * 60)))
                .isInstanceOf(BotContinuationConflictException.class)
                .hasMessage("Continuation renewal is not available yet");

        adapter.renewOwned(BOT_ID, OWNER_ID, NOW).orElseThrow();
        assertThatThrownBy(() -> adapter.renewOwned(BOT_ID, OWNER_ID, NOW.plusSeconds(1)))
                .isInstanceOf(BotContinuationConflictException.class)
                .hasMessage("Continuation renewal is not available yet");

        jdbc.update(
                "update bot.continuation_deadlines set due_at = ?, last_renewed_at = null where bot_id = ?",
                NOW.atOffset(ZoneOffset.UTC),
                BOT_ID);
        assertThatThrownBy(() -> adapter.renewOwned(BOT_ID, OWNER_ID, NOW))
                .isInstanceOf(BotContinuationConflictException.class)
                .hasMessage("Continuation deadline has expired");
    }

    @Test
    void rejectsRoomAffiliatedAndNonRunningBots() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'Room', 'PUBLIC', 'RECRUITING', ?)",
                ROOM_ID, OWNER_ID, at);
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at) "
                        + "values (?, ?, ?, ?, 'private-bot', 'REGISTERED', ?)",
                UUID.randomUUID(), ROOM_ID, BOT_ID, OWNER_ID, at);

        assertThatThrownBy(() -> adapter.renewOwned(BOT_ID, OWNER_ID, NOW))
                .isInstanceOf(BotContinuationConflictException.class)
                .hasMessage("A room-affiliated bot cannot renew continuation");
        assertThat(adapter.findOwned(BOT_ID, OWNER_ID)).isEmpty();

        jdbc.update("delete from competition.participations where bot_id = ?", BOT_ID);
        jdbc.update("update bot.bots set lifecycle_status = 'STOPPING' where id = ?", BOT_ID);
        assertThatThrownBy(() -> adapter.renewOwned(BOT_ID, OWNER_ID, NOW))
                .isInstanceOf(BotContinuationConflictException.class)
                .hasMessage("Only a running bot can renew continuation");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BotContinuationJooqAdapter.class)
    static class TestApplication {}
}
