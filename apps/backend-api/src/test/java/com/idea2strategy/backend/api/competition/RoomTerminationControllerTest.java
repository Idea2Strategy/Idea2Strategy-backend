package com.idea2strategy.backend.api.competition;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.ParticipationExitAction;
import com.idea2strategy.backend.application.competition.PlatformRoomInvalidationService;
import com.idea2strategy.backend.application.competition.RoomTerminationConflictException;
import com.idea2strategy.backend.application.competition.RoomTerminationResult;
import com.idea2strategy.backend.application.competition.UserRoomTerminationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoomTerminationControllerTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID PARTICIPATION_ID = id(2);
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

    @Test
    void acceptsAnExplicitWithdrawalOutcome() throws Exception {
        var service = mock(UserRoomTerminationService.class);
        when(service.withdraw(
                        eq(ROOM_ID), eq(PARTICIPATION_ID),
                        eq(ParticipationExitAction.CONTINUE_PRIVATE), eq("USER_REQUESTED")))
                .thenReturn(new RoomTerminationResult(ROOM_ID, 1, NOW));
        MockMvc mvc = mvc(new RoomTerminationController(service));

        mvc.perform(post("/api/v1/competition/rooms/{roomId}/participations/{participationId}/withdrawal",
                                ROOM_ID, PARTICIPATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"CONTINUE_PRIVATE","reasonCode":"USER_REQUESTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationsTerminated").value(1))
                .andExpect(jsonPath("$.occurredAt").value(NOW.toString()));
    }

    @Test
    void mapsStageConflictsWithoutChangingTheRequestMeaning() throws Exception {
        var service = mock(UserRoomTerminationService.class);
        when(service.cancel(eq(ROOM_ID), any()))
                .thenThrow(new RoomTerminationConflictException("A creator can cancel only before submission opens"));
        MockMvc mvc = mvc(new RoomTerminationController(service));

        mvc.perform(post("/api/v1/competition/rooms/{roomId}/cancellation", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"TOO_LATE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Room termination rejected"));
    }

    @Test
    void exposesThePlatformInvalidationBoundarySeparately() throws Exception {
        var service = mock(PlatformRoomInvalidationService.class);
        when(service.invalidate(ROOM_ID, "LEGAL_REQUIREMENT"))
                .thenReturn(new RoomTerminationResult(ROOM_ID, 2, NOW));
        MockMvc mvc = mvc(new PlatformRoomInvalidationController(service));

        mvc.perform(post("/api/v1/operations/competition/rooms/{roomId}/invalidation", ROOM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"LEGAL_REQUIREMENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationsTerminated").value(2));
    }

    @Test
    void expelsWithoutAcceptingAReasonPayload() throws Exception {
        var service = mock(UserRoomTerminationService.class);
        when(service.expel(ROOM_ID, PARTICIPATION_ID))
                .thenReturn(new RoomTerminationResult(ROOM_ID, 1, NOW));
        MockMvc mvc = mvc(new RoomTerminationController(service));

        mvc.perform(post("/api/v1/competition/rooms/{roomId}/participations/{participationId}/expulsion",
                        ROOM_ID, PARTICIPATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participationsTerminated").value(1));
    }

    private static MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();
    }

    private static UUID id(int suffix) {
        return UUID.fromString("88000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}
