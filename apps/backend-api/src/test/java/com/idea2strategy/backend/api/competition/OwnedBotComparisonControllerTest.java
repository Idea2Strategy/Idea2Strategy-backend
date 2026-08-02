package com.idea2strategy.backend.api.competition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.AnonymousLeaderboardItem;
import com.idea2strategy.backend.application.competition.LeaderboardQueryResult;
import com.idea2strategy.backend.application.competition.LeaderboardQueryRow;
import com.idea2strategy.backend.application.competition.OwnedBotComparisonQueryService;
import com.idea2strategy.backend.application.competition.OwnedLeaderboardEvidence;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnedBotComparisonControllerTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID OWNER_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID PARTICIPATION_ID = id(4);

    @Test
    void exposesOnlyTheViewerOwnedComparisonShape() throws Exception {
        var result = new LeaderboardQueryResult(
                id(5), "FINAL", Instant.parse("2026-08-02T05:00:00Z"),
                List.of(new LeaderboardQueryRow(
                        "sha256:" + "1".repeat(64),
                        new AnonymousLeaderboardItem(
                                1, false, "mine", BigDecimal.TEN, "ELIGIBLE",
                                BigDecimal.valueOf(100_000), BigDecimal.ONE, BigDecimal.ZERO,
                                BigDecimal.ONE,
                                new OwnedLeaderboardEvidence(BOT_ID, PARTICIPATION_ID, id(6), null, "MY_REASON")))));
        var service = new OwnedBotComparisonQueryService(query -> result, () -> OWNER_ID);
        var mvc = MockMvcBuilders.standaloneSetup(new OwnedBotComparisonController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/competition/rooms/{roomId}/leaderboard/my-bots", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].anonymousAlias").value("mine"))
                .andExpect(jsonPath("$.items[0].evidence.botId").value(BOT_ID.toString()))
                .andExpect(jsonPath("$.items[0].evidence.participationId").value(PARTICIPATION_ID.toString()))
                .andExpect(jsonPath("$.items[0].viewerEvidence").doesNotExist())
                .andExpect(jsonPath("$.items[0].ownerAccountId").doesNotExist())
                .andExpect(jsonPath("$.items[0].strategyId").doesNotExist())
                .andExpect(jsonPath("$.items[0].tradeId").doesNotExist())
                .andExpect(jsonPath("$.items[0].calculationDocument").doesNotExist())
                .andExpect(jsonPath("$.items[0].tieBreakDocument").doesNotExist());
    }

    @Test
    void mapsAuthenticationAndMalformedCursorToExistingLeaderboardBoundaries() throws Exception {
        var anonymous = new OwnedBotComparisonQueryService(
                query -> LeaderboardQueryResult.empty(), () -> null);
        var anonymousMvc = MockMvcBuilders.standaloneSetup(new OwnedBotComparisonController(anonymous))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();
        anonymousMvc.perform(get("/api/v1/competition/rooms/{roomId}/leaderboard/my-bots", ROOM_ID))
                .andExpect(status().isUnauthorized());

        var authenticated = new OwnedBotComparisonQueryService(
                query -> LeaderboardQueryResult.empty(), () -> OWNER_ID);
        var authenticatedMvc = MockMvcBuilders.standaloneSetup(new OwnedBotComparisonController(authenticated))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();
        authenticatedMvc.perform(get("/api/v1/competition/rooms/{roomId}/leaderboard/my-bots", ROOM_ID)
                        .queryParam("cursor", "%%%"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid leaderboard cursor"));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("97000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}
