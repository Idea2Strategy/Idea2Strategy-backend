package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserCompetitionRoomCreationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-01T14:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("62000000-0000-4000-8000-000000000001");
    private static final UUID CREATOR_ID = UUID.fromString("62000000-0000-4000-8000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("62000000-0000-4000-8000-000000000003");
    private static final UUID FEE_POLICY_ID = UUID.fromString("62000000-0000-4000-8000-000000000004");
    private static final UUID BUFFER_POLICY_ID = UUID.fromString("62000000-0000-4000-8000-000000000005");

    @Test
    void createsPublicAndSecretRoomsWithValidatedRules() {
        var saved = new ArrayList<CompetitionRoom>();
        var service = service(saved, List.of(templateRecord()));

        var publicRoom = service.create(command(RoomAccessType.PUBLIC, validSchedule()));
        var secretRoom = service.create(command(RoomAccessType.SECRET, validSchedule()));

        assertThat(saved).containsExactly(publicRoom, secretRoom);
        assertThat(publicRoom.accessType()).isEqualTo(RoomAccessType.PUBLIC);
        assertThat(secretRoom.accessType()).isEqualTo(RoomAccessType.SECRET);
        assertThat(publicRoom.creatorAccountId()).isEqualTo(CREATOR_ID);
        assertThat(publicRoom.scoringParameters()).isEqualTo("{\"minimumTrades\":5}");
        assertThat(publicRoom.liveRules().minimumOperationSeconds()).isEqualTo(3600);
        assertThat(publicRoom.liveRules().minimumFillCount()).isEqualTo(5);
    }

    @Test
    void rejectsUnpublishedTemplateAndInvalidLiveScheduleBeforeSaving() {
        var saved = new ArrayList<CompetitionRoom>();
        var missingTemplateService = service(saved, List.of());

        assertThatThrownBy(() -> missingTemplateService.create(command(RoomAccessType.PUBLIC, validSchedule())))
                .isInstanceOf(ScoringTemplateNotFoundException.class);

        var invalidLiveSchedule = new RoomSchedule(
                NOW.plusSeconds(60),
                NOW.plusSeconds(120),
                NOW.plusSeconds(180),
                NOW.plusSeconds(240),
                NOW.plusSeconds(300),
                NOW.plusSeconds(360),
                "UTC");
        var validTemplateService = service(saved, List.of(templateRecord()));

        assertThatThrownBy(() -> validTemplateService.create(command(RoomAccessType.SECRET, invalidLiveSchedule)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIVE_PAPER");
        assertThat(saved).isEmpty();
    }

    @Test
    void rejectsInvalidCapitalCapacityAndLiveThresholdsBeforeSaving() {
        var saved = new ArrayList<CompetitionRoom>();
        var service = service(saved, List.of(templateRecord()));

        assertThatThrownBy(() -> service.create(command(
                        RoomAccessType.PUBLIC,
                        validSchedule(),
                        new BigDecimal("0"),
                        10,
                        3600,
                        5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialCashAmount");
        assertThatThrownBy(() -> service.create(command(
                        RoomAccessType.PUBLIC,
                        validSchedule(),
                        new BigDecimal("100000"),
                        0,
                        3600,
                        5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("participation limits");
        assertThatThrownBy(() -> service.create(command(
                        RoomAccessType.PUBLIC,
                        validSchedule(),
                        new BigDecimal("100000"),
                        10,
                        -1,
                        5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumOperationSeconds");
        assertThatThrownBy(() -> service.create(command(
                        RoomAccessType.PUBLIC,
                        validSchedule(),
                        new BigDecimal("100000"),
                        10,
                        3600,
                        -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumFillCount");
        assertThat(saved).isEmpty();
    }

    private static UserCompetitionRoomCreationService service(
            List<CompetitionRoom> saved, List<ScoringTemplateCatalogRecord> templates) {
        CompetitionRoomCommandPort commandPort = saved::add;
        var catalog = new ScoringTemplateCatalogService(
                new StubCatalogPort(templates), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        return new UserCompetitionRoomCreationService(
                commandPort,
                catalog,
                () -> CREATOR_ID,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> ROOM_ID,
                new ObjectMapper());
    }

    private static CreateUserLiveRoomCommand command(RoomAccessType accessType, RoomSchedule schedule) {
        return command(
                accessType,
                schedule,
                new BigDecimal("100000.00000000"),
                10,
                3600,
                5);
    }

    private static CreateUserLiveRoomCommand command(
            RoomAccessType accessType,
            RoomSchedule schedule,
            BigDecimal initialCashAmount,
            int botParticipationLimit,
            long minimumOperationSeconds,
            int minimumFillCount) {
        return new CreateUserLiveRoomCommand(
                "August room",
                accessType,
                TEMPLATE_ID,
                Map.of("minimumTrades", new BigDecimal("5")),
                initialCashAmount,
                botParticipationLimit,
                1,
                "COUNT_UNTIL_END",
                minimumOperationSeconds,
                minimumFillCount,
                FEE_POLICY_ID,
                BUFFER_POLICY_ID,
                schedule);
    }

    private static RoomSchedule validSchedule() {
        return new RoomSchedule(
                NOW.plusSeconds(60),
                NOW.plusSeconds(120),
                NOW.plusSeconds(240),
                NOW.plusSeconds(180),
                NOW.plusSeconds(300),
                NOW.plusSeconds(360),
                "UTC");
    }

    private static ScoringTemplateCatalogRecord templateRecord() {
        return new ScoringTemplateCatalogRecord(
                TEMPLATE_ID,
                "TOTAL_RETURN",
                "1.0.0",
                """
                {
                  "kind":"SINGLE",
                  "calculationRulesVersion":"1.0.0",
                  "components":[
                    {"metric":"TOTAL_RETURN","direction":"HIGHER_IS_BETTER","coefficient":1}
                  ],
                  "adjustments":[
                    {"code":"minimumTrades","unit":"COUNT","minimum":2,"maximum":20,"scale":0}
                  ]
                }
                """,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NOW.minusSeconds(60),
                null);
    }

    private static final class StubCatalogPort implements ScoringTemplateCatalogQueryPort {
        private final List<ScoringTemplateCatalogRecord> records;

        private StubCatalogPort(List<ScoringTemplateCatalogRecord> records) {
            this.records = records;
        }

        @Override
        public List<ScoringTemplateCatalogRecord> findSelectableAt(Instant at) {
            return records;
        }

        @Override
        public Optional<ScoringTemplateCatalogRecord> findSelectableById(UUID id, Instant at) {
            return records.stream().filter(record -> record.id().equals(id)).findFirst();
        }
    }
}
