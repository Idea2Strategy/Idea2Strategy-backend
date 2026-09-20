package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.BacktestEvaluationPlanDefinition;
import com.idea2strategy.backend.application.competition.CreateOfficialBacktestRoomCommand;
import com.idea2strategy.backend.application.competition.OfficialBacktestCompetitionRoomCreationService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@org.springframework.boot.test.context.SpringBootTest(
        classes = OfficialBacktestRoomCreationPersistenceIntegrationTest.TestApplication.class)
class OfficialBacktestRoomCreationPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-10T04:00:00Z");

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

    @Autowired OfficialBacktestRoomJooqCommandAdapter commandAdapter;
    @Autowired ScoringTemplateCatalogService scoringCatalog;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsTheRoomAndAllHiddenInputsAtomicallyOnlyAfterProductionPreflight() {
        seedCatalogAndInputs();
        var created = service(ROOM).create(command(hash('4')));

        assertThat(created.competitionType().name()).isEqualTo("BACKTEST");
        assertThat(created.status().name()).isEqualTo("DRAFT");
        assertThat(text("select competition_type::text from competition.rooms where id = ?", ROOM))
                .isEqualTo("BACKTEST");
        assertThat(count("select count(*) from competition.backtest_evaluation_plans where room_id = ?", ROOM))
                .isOne();
        assertThat(count("select count(*) from competition.backtest_evaluation_periods "
                + "where evaluation_plan_room_id = ?", ROOM)).isEqualTo(2);
        assertThat(count("select count(*) from competition.backtest_period_datasets dataset "
                + "join competition.backtest_evaluation_periods period "
                + "on period.id = dataset.evaluation_period_id where period.evaluation_plan_room_id = ?", ROOM))
                .isEqualTo(2);
        assertThat(count("select count(*) from competition.live_room_rules where room_id = ?", ROOM)).isZero();

        assertThatThrownBy(() -> service(BAD_ROOM).create(command(hash('9'))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset is unavailable, changed, or does not cover");
        assertThat(count("select count(*) from competition.rooms where id = ?", BAD_ROOM)).isZero();
        assertThat(count("select count(*) from competition.backtest_evaluation_plans where room_id = ?", BAD_ROOM))
                .isZero();
    }

    private OfficialBacktestCompetitionRoomCreationService service(UUID roomId) {
        return new OfficialBacktestCompetitionRoomCreationService(
                commandAdapter,
                scoringCatalog,
                () -> Optional.of(OPERATOR),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> roomId);
    }

    private CreateOfficialBacktestRoomCommand command(String lockedDatasetHash) {
        var dataset = new BacktestEvaluationPlanDefinition.Dataset(
                DATASET, "MARKET_BARS", lockedDatasetHash);
        return new CreateOfficialBacktestRoomCommand(
                "INT04-A locked official room",
                RoomAccessType.PUBLIC,
                TEMPLATE,
                new BigDecimal("100000.00000000"),
                10,
                2,
                FEE,
                BUFFER,
                Map.of("minimumAccountState", "ACTIVE"),
                Map.of("market", "US"),
                "v1",
                new RoomSchedule(
                        NOW.plusSeconds(10),
                        NOW.plusSeconds(20),
                        NOW.plusSeconds(30),
                        NOW.plusSeconds(120),
                        NOW.plusSeconds(180),
                        NOW.plusSeconds(240),
                        "UTC"),
                "int04-a-plan.v1",
                PLAN_HASH,
                hash('5'),
                "kms:ciphertext:int04-a",
                1,
                List.of(
                        new CreateOfficialBacktestRoomCommand.Period(
                                LocalDate.parse("2024-01-01"),
                                LocalDate.parse("2024-06-30"),
                                new BigDecimal("0.5"),
                                hash('6'),
                                List.of(dataset),
                                List.of()),
                        new CreateOfficialBacktestRoomCommand.Period(
                                LocalDate.parse("2024-07-01"),
                                LocalDate.parse("2024-12-31"),
                                new BigDecimal("0.5"),
                                hash('7'),
                                List.of(dataset),
                                List.of())));
    }

    private void seedCatalogAndInputs() {
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, status, created_at) values (?, 'ACTIVE', ?)",
                OPERATOR, utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) values "
                        + "(?, 'SINGLE_TOTAL_RETURN_V1', 'int04-a-room', ?::jsonb, ?, ?)",
                TEMPLATE,
                "{\"kind\":\"SINGLE\",\"calculationRulesVersion\":\"official-room-scoring.v1\","
                        + "\"components\":[{\"metric\":\"TOTAL_RETURN\","
                        + "\"direction\":\"HIGHER_IS_BETTER\",\"coefficient\":1}],\"adjustments\":[]}",
                hash('1'), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'INT04_A_ROOM', '1', 20, 'v1', ?, ?, ?)",
                FEE, hash('2'), utc(NOW.minusSeconds(100)), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'INT04_A_ROOM', '1', 0, 'v1', ?, ?, ?)",
                BUFFER, hash('3'), utc(NOW.minusSeconds(100)), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into backtest.execution_policy_versions "
                        + "(version, policy_artifact_hash, policy_document, locked_at) "
                        + "values ('int04-a-room-v1', ?, jsonb_build_object('competitionPlanHash', ?), ?)",
                hash('8'), PLAN_HASH, utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into market_data.providers "
                        + "(id, code, display_name, rights_version, status, created_at) "
                        + "values (?, 'INT04_A_PROVIDER', 'INT04-A official', 'internal-v1', 'ACTIVE', ?)",
                PROVIDER, utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'INT04_A_DAILY', 'BAR', '1d', 'UTC', 'v1', ?)",
                FEED, PROVIDER, utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into market_data.dataset_manifests "
                        + "(id, feed_id, data_layer, resolution, revision_number, status, period_start, period_end, "
                        + "schema_version, dataset_hash, created_at, available_at) values "
                        + "(?, ?, 'ADJUSTED', '1d', 1, 'AVAILABLE', '2023-12-01T00:00:00Z', "
                        + "'2025-01-31T23:59:59Z', 'v1', ?, ?, ?)",
                DATASET, FEED, "4".repeat(64), utc(NOW.minusSeconds(100)), utc(NOW.minusSeconds(100)));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String hash(char digit) {
        return "sha256:" + Character.toString(digit).repeat(64);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a0410000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final UUID OPERATOR = id(1);
    private static final UUID TEMPLATE = id(2);
    private static final UUID FEE = id(3);
    private static final UUID BUFFER = id(4);
    private static final UUID PROVIDER = id(5);
    private static final UUID FEED = id(6);
    private static final UUID DATASET = id(7);
    private static final UUID ROOM = id(8);
    private static final UUID BAD_ROOM = id(9);
    private static final String PLAN_HASH = "sha256:" + "e".repeat(64);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ScoringTemplateCatalogJooqQueryAdapter.class, OfficialBacktestRoomJooqCommandAdapter.class})
    static class TestApplication {
        @Bean
        ScoringTemplateCatalogService scoringTemplateCatalogService(
                ScoringTemplateCatalogJooqQueryAdapter adapter) {
            return new ScoringTemplateCatalogService(
                    adapter, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        }
    }
}
