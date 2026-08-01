package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.PublicRoomDiscoveryService;
import com.idea2strategy.backend.application.competition.PublicRoomPage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms/public")
@ConditionalOnBean(PublicRoomDiscoveryService.class)
public class PublicRoomDiscoveryController {
    private final PublicRoomDiscoveryService discoveryService;

    public PublicRoomDiscoveryController(PublicRoomDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @GetMapping
    public PublicRoomPage search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return discoveryService.search(q, cursor, limit);
    }
}
