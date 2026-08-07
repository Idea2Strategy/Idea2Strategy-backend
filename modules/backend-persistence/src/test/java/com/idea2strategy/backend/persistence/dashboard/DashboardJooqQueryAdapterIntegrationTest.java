package com.idea2strategy.backend.persistence.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

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
@SpringBootTest(classes = DashboardJooqQueryAdapterIntegrationTest.TestApplication.class)
class DashboardJooqQueryAdapterIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID ROOM_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID PARTICIPATION_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

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
    private DashboardJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareData() {
        jdbc.update("delete from performance.bot_current_projections");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.bots");
        jdbc.execute("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id in (?, ?)", OWNER_ID, OTHER_OWNER_ID);
        insertAccount(OWNER_ID);
        insertAccount(OTHER_OWNER_ID);
        insertBot(BOT_ID, OWNER_ID, "Owner bot");
        insertBot(OTHER_BOT_ID, OTHER_OWNER_ID, "Other bot");
        insertPerformance();
        insertCompetition();
    }

    @Test
    void joinsOnlyOwnedBotsToTheirCurrentPerformanceAndCompetition() {
        var bots = adapter.findOwned(OWNER_ID);

        assertThat(bots).singleElement().satisfies(bot -> {
            assertThat(bot.botId()).isEqualTo(BOT_ID);
            assertThat(bot.performance()).isNotNull();
            assertThat(bot.performance().equityAmount()).isEqualByComparingTo("10540.00");
            assertThat(bot.competition()).isNotNull();
            assertThat(bot.competition().roomId()).isEqualTo(ROOM_ID);
            assertThat(bot.competition().timezoneName()).isEqualTo("Asia/Seoul");
        });
    }

    private void insertAccount(UUID accountId) {
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values (?, 'ACTIVE', ?)",
                accountId,
                NOW.atOffset(ZoneOffset.UTC));
    }

    private void insertBot(UUID botId, UUID ownerId, String name) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, ?)",
                botId,
                ownerId,
                name,
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC));
    }

    private void insertPerformance() {
        jdbc.update(
                "insert into performance.bot_current_projections "
                        + "(bot_id, equity_amount, total_return_pct, max_drawdown_pct, sharpe_ratio, "
                        + "metrics_document, ledger_state_hash, position_state_hash, calculation_rules_version, "
                        + "last_event_sequence, projection_hash, updated_at) "
                        + "values (?, 10540, 5.4, -2.1, 1.25, '{}'::jsonb, 'ledger', 'positions', "
                        + "'performance-v1', 8, 'projection', ?)",
                BOT_ID,
                NOW.minusSeconds(30).atOffset(ZoneOffset.UTC));
    }

    private void insertCompetition() {
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'Momentum Lab', 'PUBLIC', 'EVALUATING', ?)",
                ROOM_ID,
                OWNER_ID,
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'Asia/Seoul')",
                ROOM_ID,
                NOW.minusSeconds(7200).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(7100).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(86400).atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(90000).atOffset(ZoneOffset.UTC));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, evaluation_started_at) "
                        + "values (?, ?, ?, ?, 'owner-bot', 'EVALUATING', ?, ?)",
                PARTICIPATION_ID,
                ROOM_ID,
                BOT_ID,
                OWNER_ID,
                NOW.minusSeconds(7100).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(DashboardJooqQueryAdapter.class)
    static class TestApplication {}
}
