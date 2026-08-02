package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.OperatorRoomManagementService;
import com.idea2strategy.backend.application.competition.OperatorRoomView;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/competition/rooms/{roomId}")
@ConditionalOnBean(OperatorRoomManagementService.class)
public class OperatorRoomManagementController {
    private final OperatorRoomManagementService service;

    public OperatorRoomManagementController(OperatorRoomManagementService service) {
        this.service = service;
    }

    @GetMapping
    public OperatorRoomView view(@PathVariable UUID roomId) {
        return service.view(roomId);
    }

    @PostMapping("/cancellation")
    public RoomTerminationController.TerminationResponse cancel(
            @PathVariable UUID roomId, @RequestBody RoomTerminationController.ReasonRequest request) {
        return RoomTerminationController.response(service.cancel(roomId, request.reasonCode()));
    }
}
