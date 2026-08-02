package com.idea2strategy.backend.api.competition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.CompetitionRoomCommandPort;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogQueryPort;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogRecord;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.application.competition.UserCompetitionRoomCreationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CompetitionRoomControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-01T14:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("63000000-0000-4000-8000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("63000000-0000-4000-8000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("63000000-0000-4000-8000-000000000003");

    @Test
    void createsASecretRoomForTheCurrentPrincipal() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CompetitionRoomController(service())).build();

        mvc.perform(post("/api/v1/competition/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "name":"August secret room",
                          "accessType":"SECRET",
                          "scoringTemplateVersionId":"63000000-0000-4000-8000-000000000003",
                          "scoringAdjustments":{"minimumTrades":5},
                          "initialCashAmount":100000.00000000,
                          "botParticipationLimit":10,
                          "perAccountBotLimit":1,
                          "stoppedBotSlotPolicy":"COUNT_UNTIL_END",
                          "minimumOperationSeconds":3600,
                          "minimumFillCount":5,
                          "feePolicyId":"63000000-0000-4000-8000-000000000004",
                          "buyingPowerBufferPolicyId":"63000000-0000-4000-8000-000000000005",
                          "recruitmentOpensAt":"2026-08-01T14:01:00Z",
                          "participationOpensAt":"2026-08-01T14:02:00Z",
                          "evaluationStartsAt":"2026-08-01T14:04:00Z",
                          "participationClosesAt":"2026-08-01T14:03:00Z",
                          "evaluationEndsAt":"2026-08-01T15:04:00Z",
                          "finalizationDeadlineAt":"2026-08-01T15:05:00Z",
                          "timezoneName":"UTC"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.accessType").value("SECRET"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    private static UserCompetitionRoomCreationService service() {
        CompetitionRoomCommandPort commandPort = room -> {
            if (!room.creatorAccountId().equals(OWNER_ID)) {
                throw new AssertionError("room creator must come from CurrentPrincipal");
            }
        };
        var scoringCatalog = new ScoringTemplateCatalogService(
                new StubCatalogPort(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        return new UserCompetitionRoomCreationService(
                commandPort,
                scoringCatalog,
                () -> OWNER_ID,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> ROOM_ID,
                new ObjectMapper());
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
