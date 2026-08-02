package com.idea2strategy.backend.api.competition;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.PostEvaluationAction;
import com.idea2strategy.backend.application.competition.PostEvaluationChoice;
import com.idea2strategy.backend.application.competition.PostEvaluationChoicePort;
import com.idea2strategy.backend.application.competition.UserPostEvaluationChoiceService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PostEvaluationChoiceControllerTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID PARTICIPATION_ID = id(2);
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

    @Test
    void exposesTheUndecidedStateWithoutAnImplicitDefault() throws Exception {
        var service = mock(UserPostEvaluationChoiceService.class);
        when(service.find(ROOM_ID, PARTICIPATION_ID))
                .thenReturn(new PostEvaluationChoice(ROOM_ID, PARTICIPATION_ID, null, null, null));

        mvc(service)
                .perform(get(path(), ROOM_ID, PARTICIPATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").doesNotExist())
                .andExpect(jsonPath("$.recordedAt").doesNotExist());
    }

    @Test
    void recordsAnExplicitPostEvaluationAction() throws Exception {
        var service = mock(UserPostEvaluationChoiceService.class);
        when(service.update(ROOM_ID, PARTICIPATION_ID, PostEvaluationAction.STOP_AFTER_EVALUATION))
                .thenReturn(new PostEvaluationChoice(
                        ROOM_ID, PARTICIPATION_ID, PostEvaluationAction.STOP_AFTER_EVALUATION, NOW, null));

        mvc(service)
                .perform(put(path(), ROOM_ID, PARTICIPATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"STOP_AFTER_EVALUATION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("STOP_AFTER_EVALUATION"))
                .andExpect(jsonPath("$.recordedAt").value(NOW.toString()));
    }

    @Test
    void rejectsAMissingChoiceInsteadOfApplyingADefault() throws Exception {
        var service = new UserPostEvaluationChoiceService(
                new NoOpPort(), () -> id(9), Clock.fixed(NOW, ZoneOffset.UTC));

        mvc(service)
                .perform(put(path(), ROOM_ID, PARTICIPATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("action must be explicitly selected"));
    }

    private static MockMvc mvc(UserPostEvaluationChoiceService service) {
        return MockMvcBuilders.standaloneSetup(new PostEvaluationChoiceController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();
    }

    private static String path() {
        return "/api/v1/competition/rooms/{roomId}/participations/{participationId}/post-evaluation-choice";
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a5000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final class NoOpPort implements PostEvaluationChoicePort {
        @Override
        public PostEvaluationChoice findOwned(UUID roomId, UUID participationId, UUID ownerAccountId) {
            return new PostEvaluationChoice(roomId, participationId, null, null, null);
        }

        @Override
        public PostEvaluationChoice updateOwned(
                UUID roomId,
                UUID participationId,
                UUID ownerAccountId,
                PostEvaluationAction action,
                Instant recordedAt) {
            throw new AssertionError("missing action must not reach persistence");
        }
    }
}
