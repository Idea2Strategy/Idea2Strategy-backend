package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.AnonymousLeaderboardPage;
import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQueryService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms/{roomId}/leaderboard")
@ConditionalOnBean(AnonymousLeaderboardQueryService.class)
public class AnonymousLeaderboardController {
    private final AnonymousLeaderboardQueryService queryService;

    public AnonymousLeaderboardController(AnonymousLeaderboardQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public AnonymousLeaderboardPage query(
            @PathVariable UUID roomId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return queryService.query(roomId, cursor, limit);
    }
}
