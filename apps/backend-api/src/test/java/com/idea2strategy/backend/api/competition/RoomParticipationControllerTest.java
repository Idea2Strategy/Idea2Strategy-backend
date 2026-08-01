package com.idea2strategy.backend.api.competition;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.RoomParticipationAdmission;
import com.idea2strategy.backend.application.competition.RoomStrategyParticipationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoomParticipationControllerTest {
    @Test
    void joinsWithAValidatedStrategyWithoutAcceptingAnExistingBotId() throws Exception {
        UUID roomId = UUID.fromString("10000000-0000-4000-8000-000000000081");
        UUID participationId = UUID.fromString("20000000-0000-4000-8000-000000000081");
        UUID botId = UUID.fromString("30000000-0000-4000-8000-000000000081");
        var service = mock(RoomStrategyParticipationService.class);
        when(service.join(any())).thenReturn(new RoomParticipationAdmission(
                participationId,
                roomId,
                botId,
                UUID.fromString("40000000-0000-4000-8000-000000000081"),
                "ALPHA",
                Instant.parse("2026-08-02T01:00:00Z")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RoomParticipationController(service)).build();

        mvc.perform(post("/api/v1/competition/rooms/{roomId}/participations", roomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "validationRunId":"50000000-0000-4000-8000-000000000081",
                                  "anonymousAlias":"ALPHA",
                                  "languageVersion":"basic/v1",
                                  "schemaVersion":"schema/v1",
                                  "catalogVersion":"catalog/v1",
                                  "budgetCapBps":10000,
                                  "brokerRulesVersion":"broker/v1",
                                  "accountingRulesVersion":"accounting/v1",
                                  "candidateConflictPolicy":{"policy":"FIRST_WINS"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(participationId.toString()))
                .andExpect(jsonPath("$.roomId").value(roomId.toString()))
                .andExpect(jsonPath("$.botId").value(botId.toString()))
                .andExpect(jsonPath("$.anonymousAlias").value("ALPHA"));
    }
}
