package com.idea2strategy.backend.api.competition;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.OperatorRoomManagementService;
import com.idea2strategy.backend.application.competition.OperatorRoomView;
import com.idea2strategy.backend.application.competition.RoomTerminationPort;
import com.idea2strategy.backend.application.competition.RoomTerminationResult;
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

class OperatorRoomManagementControllerTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID OPERATOR_ID = id(2);
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

    @Test
    void exposesOnlyOperatorSafeRoomEvidenceAndSupportsOfficialCancellation() throws Exception {
        RoomTerminationPort termination = mock(RoomTerminationPort.class);
        when(termination.cancelOfficial(any(), any(), any(), any()))
                .thenReturn(new RoomTerminationResult(ROOM_ID, 0, NOW));
        var view = new OperatorRoomView(
                new OperatorRoomView.RoomSummary(
                        ROOM_ID, "official", "LIVE_PAPER", "PUBLIC", "ENDED",
                        NOW.minusSeconds(7200), NOW.minusSeconds(3600), NOW,
                        NOW, null, null, id(3), "rules"),
                List.of(new OperatorRoomView.RoomEvent(1, "ROOM_ENDED", "ENDED", null, NOW)),
                List.of(new OperatorRoomView.ParticipationEvent(
                        "safe-alias", 1, "PARTICIPATION_COMPLETED", null, NOW)),
                null);
        var service = new OperatorRoomManagementService(
                (operator, permission, action, room, at) -> true,
                roomId -> Optional.of(view),
                termination,
                () -> Optional.of(OPERATOR_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperatorRoomManagementController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();

        String response = mvc.perform(get("/api/v1/operations/competition/rooms/{roomId}", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.roomId").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.participationEvents[0].anonymousAlias").value("safe-alias"))
                .andExpect(jsonPath("$.room.creatorAccountId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(response)
                .doesNotContain("accountId", "botId", "participationId", "strategyId", "payloadDocument");

        mvc.perform(post("/api/v1/operations/competition/rooms/{roomId}/cancellation", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"OPERATOR_CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(ROOM_ID.toString()));
    }

    @Test
    void rejectsAnOperatorWithoutTheDatabasePermission() throws Exception {
        var service = new OperatorRoomManagementService(
                (operator, permission, action, room, at) -> false,
                roomId -> Optional.empty(),
                mock(RoomTerminationPort.class),
                () -> Optional.of(OPERATOR_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperatorRoomManagementController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/operations/competition/rooms/{roomId}", ROOM_ID))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("96000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}
