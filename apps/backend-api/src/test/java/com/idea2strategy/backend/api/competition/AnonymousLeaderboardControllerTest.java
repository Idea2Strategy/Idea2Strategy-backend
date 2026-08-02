package com.idea2strategy.backend.api.competition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.AnonymousLeaderboardItem;
import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQueryService;
import com.idea2strategy.backend.application.competition.LeaderboardAccessException;
import com.idea2strategy.backend.application.competition.LeaderboardQueryResult;
import com.idea2strategy.backend.application.competition.LeaderboardQueryRow;
import com.idea2strategy.backend.application.competition.OwnedLeaderboardEvidence;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnonymousLeaderboardControllerTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID OWNER_ID = id(2);
    private static final UUID OWN_BOT_ID = id(3);
    private static final UUID OWN_PARTICIPATION_ID = id(4);
    private static final UUID OTHER_PARTICIPATION_ID = id(5);
    private static final UUID SNAPSHOT_ID = id(6);

    @Test
    void serializesOnlyAnonymousRowsAndViewerOwnedEvidence() throws Exception {
        var result = new LeaderboardQueryResult(
                SNAPSHOT_ID, "FINAL", Instant.parse("2026-08-02T05:00:00Z"),
                List.of(
                        row(OWN_PARTICIPATION_ID, "mine", new OwnedLeaderboardEvidence(
                                OWN_BOT_ID, OWN_PARTICIPATION_ID, id(7), null, "MY_REASON")),
                        row(OTHER_PARTICIPATION_ID, "other", null)));
        var service = new AnonymousLeaderboardQueryService(query -> result, () -> OWNER_ID);
        var mvc = MockMvcBuilders.standaloneSetup(new AnonymousLeaderboardController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();

        String response = mvc.perform(get("/api/v1/competition/rooms/{roomId}/leaderboard", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].anonymousAlias").value("mine"))
                .andExpect(jsonPath("$.items[0].viewerEvidence.botId").value(OWN_BOT_ID.toString()))
                .andExpect(jsonPath("$.items[0].viewerEvidence.eligibilityReasonCode").value("MY_REASON"))
                .andExpect(jsonPath("$.items[1].anonymousAlias").value("other"))
                .andExpect(jsonPath("$.items[1].viewerEvidence").isEmpty())
                .andExpect(jsonPath("$.items[0].ownerAccountId").doesNotExist())
                .andExpect(jsonPath("$.items[0].strategyId").doesNotExist())
                .andExpect(jsonPath("$.items[0].calculationDocument").doesNotExist())
                .andExpect(jsonPath("$.items[0].tieBreakDocument").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(response)
                .doesNotContain(OTHER_PARTICIPATION_ID.toString());
    }

    @Test
    void requiresAuthenticationBeforeAnyLeaderboardAccess() throws Exception {
        var service = new AnonymousLeaderboardQueryService(
                query -> LeaderboardQueryResult.empty(), () -> null);
        var mvc = MockMvcBuilders.standaloneSetup(new AnonymousLeaderboardController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/competition/rooms/{roomId}/leaderboard", ROOM_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void mapsSecretAccessAndInvalidCursorToTheirHttpBoundaries() throws Exception {
        var denied = new AnonymousLeaderboardQueryService(
                query -> { throw new LeaderboardAccessException("secret room denied"); }, () -> OWNER_ID);
        var deniedMvc = MockMvcBuilders.standaloneSetup(new AnonymousLeaderboardController(denied))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();
        deniedMvc.perform(get("/api/v1/competition/rooms/{roomId}/leaderboard", ROOM_ID))
                .andExpect(status().isForbidden());

        var invalid = new AnonymousLeaderboardQueryService(
                query -> LeaderboardQueryResult.empty(), () -> OWNER_ID);
        var invalidMvc = MockMvcBuilders.standaloneSetup(new AnonymousLeaderboardController(invalid))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();
        invalidMvc.perform(get("/api/v1/competition/rooms/{roomId}/leaderboard", ROOM_ID)
                        .queryParam("cursor", "%%%"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid leaderboard cursor"));
    }

    private static LeaderboardQueryRow row(
            UUID participationId, String alias, OwnedLeaderboardEvidence evidence) {
        return new LeaderboardQueryRow(
                "sha256:" + String.format("%064x", participationId.hashCode() & 0xffffffffL),
                new AnonymousLeaderboardItem(
                        1, false, alias, BigDecimal.TEN, "ELIGIBLE",
                        BigDecimal.valueOf(100_000), BigDecimal.ONE, BigDecimal.ZERO,
                        BigDecimal.ONE, evidence));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("93000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}
