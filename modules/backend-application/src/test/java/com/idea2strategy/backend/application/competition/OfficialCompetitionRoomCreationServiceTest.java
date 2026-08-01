package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomOrganizerType;
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

class OfficialCompetitionRoomCreationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("66000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR_ID = UUID.fromString("66000000-0000-4000-8000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("66000000-0000-4000-8000-000000000003");
    private static final UUID FEE_POLICY_ID = UUID.fromString("66000000-0000-4000-8000-000000000004");
    private static final UUID BUFFER_POLICY_ID = UUID.fromString("66000000-0000-4000-8000-000000000005");

    @Test
    void createsAPlatformRoomWithLockedOfficialCriteriaRulesAndTemplateVersion() {
        var saved = new ArrayList<CompetitionRoom>();
        var service = service(saved, Optional.of(OPERATOR_ID));

        var room = service.create(command());

        assertThat(saved).containsExactly(room);
        assertThat(room.organizerType()).isEqualTo(RoomOrganizerType.PLATFORM);
        assertThat(room.creatorAccountId()).isNull();
        assertThat(room.createdByOperatorId()).isEqualTo(OPERATOR_ID);
        assertThat(room.scoringTemplateVersionId()).isEqualTo(TEMPLATE_ID);
        assertThat(room.eligibilityDocument()).isEqualTo("{\"minimumAccountAgeDays\":30}");
        assertThat(room.marketScopeDocument()).isEqualTo("{\"market\":\"US\"}");
        assertThat(room.precisionRulesVersion()).isEqualTo("precision-2026-08");
        assertThat(room.lockedAt()).isEqualTo(NOW);
        assertThat(room.rulesHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsANonOperatorBeforeLookingUpTemplatesOrSaving() {
        var saved = new ArrayList<CompetitionRoom>();
        var service = service(saved, Optional.empty());

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(OperatorAuthorizationException.class);
        assertThat(saved).isEmpty();
    }

    private static OfficialCompetitionRoomCreationService service(
            List<CompetitionRoom> saved, Optional<UUID> operatorId) {
        var catalog = new ScoringTemplateCatalogService(
                new StubCatalogPort(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        return new OfficialCompetitionRoomCreationService(
                saved::add,
                catalog,
                () -> operatorId,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> ROOM_ID,
                new ObjectMapper());
    }

    private static CreateOfficialLiveRoomCommand command() {
        return new CreateOfficialLiveRoomCommand(
                "Official August room",
                RoomAccessType.PUBLIC,
                TEMPLATE_ID,
                Map.of("minimumTrades", new BigDecimal("5")),
                new BigDecimal("100000.00000000"),
                100,
                1,
                "COUNT_UNTIL_END",
                3600,
                5,
                FEE_POLICY_ID,
                BUFFER_POLICY_ID,
                Map.of("minimumAccountAgeDays", 30),
                Map.of("market", "US"),
                "precision-2026-08",
                new RoomSchedule(
                        NOW.plusSeconds(60),
                        NOW.plusSeconds(120),
                        NOW.plusSeconds(240),
                        NOW.plusSeconds(180),
                        NOW.plusSeconds(300),
                        NOW.plusSeconds(360),
                        "UTC"));
    }

    private static final class StubCatalogPort implements ScoringTemplateCatalogQueryPort {
        private final ScoringTemplateCatalogRecord record = new ScoringTemplateCatalogRecord(
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

        @Override
        public List<ScoringTemplateCatalogRecord> findSelectableAt(Instant at) {
            return List.of(record);
        }

        @Override
        public Optional<ScoringTemplateCatalogRecord> findSelectableById(UUID id, Instant at) {
            return record.id().equals(id) ? Optional.of(record) : Optional.empty();
        }
    }
}
