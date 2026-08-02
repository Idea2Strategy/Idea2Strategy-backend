package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.PlatformRoomInvalidationService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/competition/rooms/{roomId}")
@ConditionalOnBean(PlatformRoomInvalidationService.class)
public class PlatformRoomInvalidationController {
    private final PlatformRoomInvalidationService service;

    public PlatformRoomInvalidationController(PlatformRoomInvalidationService service) {
        this.service = service;
    }

    @PostMapping("/invalidation")
    public RoomTerminationController.TerminationResponse invalidate(
            @PathVariable UUID roomId, @RequestBody RoomTerminationController.ReasonRequest request) {
        return RoomTerminationController.response(service.invalidate(roomId, request.reasonCode()));
    }
}
