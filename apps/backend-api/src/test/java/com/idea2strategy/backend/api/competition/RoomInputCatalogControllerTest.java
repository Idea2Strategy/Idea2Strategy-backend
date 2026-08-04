package com.idea2strategy.backend.api.competition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.RoomExecutionPolicyCatalog;
import com.idea2strategy.backend.application.competition.RoomExecutionPolicyCatalogQueryPort;
import com.idea2strategy.backend.application.competition.RoomInputCatalogQueryService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogQueryPort;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogRecord;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoomInputCatalogControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");
    private static final UUID SCORING_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID FEE_ID = UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final UUID BUFFER_ID = UUID.fromString("71000000-0000-4000-8000-000000000003");

    @Test
    void exposesOnlyServerVerifiedRoomCreationInputs() throws Exception {
        var scoring = new ScoringTemplateCatalogService(
                new ScoringPort(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        RoomExecutionPolicyCatalogQueryPort policies = at -> {
            if (!NOW.equals(at)) {
                throw new AssertionError("query must use the service clock");
            }
            return new RoomExecutionPolicyCatalog(
                    List.of(new RoomExecutionPolicyCatalog.FeePolicyVersion(
                            FEE_ID, "OFFICIAL", "1.0.0", 20, "1.0.0", digest('b'),
                            NOW.minusSeconds(60), null, NOW.minusSeconds(120))),
                    List.of(new RoomExecutionPolicyCatalog.BuyingPowerBufferPolicyVersion(
                            BUFFER_ID, "DEFAULT", "1.0.0", 100, "1.0.0", digest('c'),
                            NOW.minusSeconds(60), null, NOW.minusSeconds(120))));
        };
        var service = new RoomInputCatalogQueryService(
                scoring, policies, Clock.fixed(NOW, ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new RoomInputCatalogController(service)).build();

        mvc.perform(get("/api/v1/competition/room-input-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoringTemplates[0].id").value(SCORING_ID.toString()))
                .andExpect(jsonPath("$.scoringTemplates[0].templateCode").value("TOTAL_RETURN"))
                .andExpect(jsonPath("$.scoringTemplates[0].adjustments[0].code").value("minimumTrades"))
                .andExpect(jsonPath("$.feePolicies[0].id").value(FEE_ID.toString()))
                .andExpect(jsonPath("$.feePolicies[0].feeRateBps").value(20))
                .andExpect(jsonPath("$.buyingPowerBufferPolicies[0].id").value(BUFFER_ID.toString()))
                .andExpect(jsonPath("$.buyingPowerBufferPolicies[0].bufferBps").value(100));
    }

    private static final class ScoringPort implements ScoringTemplateCatalogQueryPort {
        private final ScoringTemplateCatalogRecord record = new ScoringTemplateCatalogRecord(
                SCORING_ID,
                "TOTAL_RETURN",
                "1.0.0",
                """
                {"kind":"SINGLE","calculationRulesVersion":"1.0.0",
                 "components":[{"metric":"TOTAL_RETURN","direction":"HIGHER_IS_BETTER","coefficient":1}],
                 "adjustments":[{"code":"minimumTrades","unit":"COUNT","minimum":1,"maximum":20,"scale":0}]}
                """,
                digest('a'),
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

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
