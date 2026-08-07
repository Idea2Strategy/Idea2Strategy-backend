package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.OwnedRoomManagementQueryService;
import com.idea2strategy.backend.application.competition.OwnedRoomManagementView;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms/mine")
@ConditionalOnBean(OwnedRoomManagementQueryService.class)
public class OwnedRoomManagementController {
    private final OwnedRoomManagementQueryService service;

    public OwnedRoomManagementController(OwnedRoomManagementQueryService service) {
        this.service = service;
    }

    @GetMapping
    public Response list(@RequestParam(defaultValue = "50") int limit) {
        return new Response(service.list(limit));
    }

    public record Response(List<OwnedRoomManagementView> items) {}
}
