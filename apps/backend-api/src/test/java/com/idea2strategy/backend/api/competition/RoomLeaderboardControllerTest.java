package com.idea2strategy.backend.api.competition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.LeaderboardQueryResult;
import com.idea2strategy.backend.application.competition.RoomLeaderboardQueryResult;
import com.idea2strategy.backend.application.competition.RoomLeaderboardQueryService;
import com.idea2strategy.backend.application.competition.RoomLeaderboardSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoomLeaderboardControllerTest {
    private static final UUID ROOM_ID = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID VIEWER_ID = UUID.fromString("94000000-0000-4000-8000-000000000002");
    private static final UUID SNAPSHOT_ID = UUID.fromString("94000000-0000-4000-8000-000000000003");
    private static final Instant CUTOFF = Instant.parse("2026-08-02T05:00:00Z");

    @Test
    void exposesTheIntegratedResultWithoutPrivateActorFields() throws Exception {
        var service = new RoomLeaderboardQueryService(query -> new RoomLeaderboardQueryResult(
                new RoomLeaderboardSummary(
                        ROOM_ID, "Integrated", "LIVE_PAPER", "USER", "PUBLIC", "ENDED",
                        CUTOFF.minusSeconds(3600), CUTOFF, CUTOFF,
                        UUID.fromString("94000000-0000-4000-8000-000000000004"), "rules"),
                List.of(),
                new LeaderboardQueryResult(SNAPSHOT_ID, "FINAL", CUTOFF, List.of()),
                new LeaderboardQueryResult(SNAPSHOT_ID, "FINAL", CUTOFF, List.of())), () -> VIEWER_ID);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RoomLeaderboardController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/competition/rooms/{roomId}/results", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.room.roomId").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.room.creatorAccountId").doesNotExist())
                .andExpect(jsonPath("$.leaderboard.snapshotId").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.myBots.snapshotId").value(SNAPSHOT_ID.toString()));
    }
}
