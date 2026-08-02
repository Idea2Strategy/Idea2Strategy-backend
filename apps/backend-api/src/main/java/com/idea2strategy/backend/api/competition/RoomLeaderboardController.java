package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.RoomLeaderboardPage;
import com.idea2strategy.backend.application.competition.RoomLeaderboardQueryService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms/{roomId}/results")
@ConditionalOnBean(RoomLeaderboardQueryService.class)
public class RoomLeaderboardController {
    private final RoomLeaderboardQueryService queryService;

    public RoomLeaderboardController(RoomLeaderboardQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public RoomLeaderboardPage query(
            @PathVariable UUID roomId,
            @RequestParam(required = false) String leaderboardCursor,
            @RequestParam(defaultValue = "20") int leaderboardLimit,
            @RequestParam(required = false) String myBotsCursor,
            @RequestParam(defaultValue = "20") int myBotsLimit) {
        return queryService.query(
                roomId, leaderboardCursor, leaderboardLimit, myBotsCursor, myBotsLimit);
    }
}
