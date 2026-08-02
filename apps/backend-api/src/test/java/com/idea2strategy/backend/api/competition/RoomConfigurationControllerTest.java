package com.idea2strategy.backend.api.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.RoomConfigurationUpdate;
import com.idea2strategy.backend.application.competition.RoomConfigurationUpdateOutcome;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogQueryPort;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogRecord;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.application.competition.UserRoomConfigurationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoomConfigurationControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("65000000-0000-4000-8000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("65000000-0000-4000-8000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("65000000-0000-4000-8000-000000000003");

    @Test
    void replacesTheOwnedDraftConfiguration() throws Exception {
        var captured = new AtomicReference<RoomConfigurationUpdate>();
        var service = new UserRoomConfigurationService(
                update -> {
                    captured.set(update);
                    return RoomConfigurationUpdateOutcome.UPDATED;
                },
                new ScoringTemplateCatalogService(
                        new CatalogPort(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper()),
                () -> OWNER_ID,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(new RoomConfigurationController(service)).build();

        mvc.perform(put("/api/v1/competition/rooms/{roomId}/configuration", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "name":"Updated room",
                          "accessType":"PUBLIC",
                          "scoringTemplateVersionId":"65000000-0000-4000-8000-000000000003",
                          "scoringAdjustments":{"minimumTrades":5},
                          "initialCashAmount":200000.00000000,
                          "botParticipationLimit":8,
                          "perAccountBotLimit":2,
                          "stoppedBotSlotPolicy":"RETURN_ON_STOP",
                          "minimumOperationSeconds":7200,
                          "minimumFillCount":10,
                          "feePolicyId":"65000000-0000-4000-8000-000000000004",
                          "buyingPowerBufferPolicyId":"65000000-0000-4000-8000-000000000005",
                          "recruitmentOpensAt":"2026-08-02T00:01:00Z",
                          "participationOpensAt":"2026-08-02T00:02:00Z",
                          "evaluationStartsAt":"2026-08-02T00:04:00Z",
                          "participationClosesAt":"2026-08-02T00:03:00Z",
                          "evaluationEndsAt":"2026-08-02T00:05:00Z",
                          "finalizationDeadlineAt":"2026-08-02T00:06:00Z",
                          "timezoneName":"UTC"
                        }
                        """))
                .andExpect(status().isNoContent());

        assertThat(captured.get().roomId()).isEqualTo(ROOM_ID);
        assertThat(captured.get().initialCashAmount()).isEqualByComparingTo("200000.00000000");
    }

    private static final class CatalogPort implements ScoringTemplateCatalogQueryPort {
        private final ScoringTemplateCatalogRecord record = new ScoringTemplateCatalogRecord(
                TEMPLATE_ID,
                "TOTAL_RETURN",
                "1.0.0",
                """
                {"kind":"SINGLE","calculationRulesVersion":"1.0.0",
                 "components":[{"metric":"TOTAL_RETURN","direction":"HIGHER_IS_BETTER","coefficient":1}],
                 "adjustments":[{"code":"minimumTrades","unit":"COUNT","minimum":2,"maximum":20,"scale":0}]}
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
            return TEMPLATE_ID.equals(id) ? Optional.of(record) : Optional.empty();
        }
    }
}
