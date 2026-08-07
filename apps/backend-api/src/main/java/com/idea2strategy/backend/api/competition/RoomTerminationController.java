package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.ParticipationExitAction;
import com.idea2strategy.backend.application.competition.RoomTerminationResult;
import com.idea2strategy.backend.application.competition.UserRoomTerminationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms/{roomId}")
@ConditionalOnBean(UserRoomTerminationService.class)
public class RoomTerminationController {
    private final UserRoomTerminationService service;

    public RoomTerminationController(UserRoomTerminationService service) {
        this.service = service;
    }

    @PostMapping("/participations/{participationId}/withdrawal")
    public TerminationResponse withdraw(
            @PathVariable UUID roomId,
            @PathVariable UUID participationId,
            @RequestBody WithdrawalRequest request) {
        return response(service.withdraw(roomId, participationId, request.action(), request.reasonCode()));
    }

    @PostMapping("/cancellation")
    public TerminationResponse cancel(@PathVariable UUID roomId, @RequestBody ReasonRequest request) {
        return response(service.cancel(roomId, request.reasonCode()));
    }

    @PostMapping("/participations/{participationId}/expulsion")
    public TerminationResponse expel(@PathVariable UUID roomId, @PathVariable UUID participationId) {
        return response(service.expel(roomId, participationId));
    }

    public record WithdrawalRequest(ParticipationExitAction action, String reasonCode) {
        public WithdrawalRequest {
            if (action == null) {
                throw new IllegalArgumentException("Withdrawal action is required");
            }
            if (reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 80) {
                throw new IllegalArgumentException("reasonCode must contain 1 to 80 characters");
            }
        }
    }
    public record ReasonRequest(String reasonCode) {}
    public record TerminationResponse(UUID roomId, int participationsTerminated, Instant occurredAt) {}

    static TerminationResponse response(RoomTerminationResult result) {
        return new TerminationResponse(result.roomId(), result.participationsTerminated(), result.occurredAt());
    }
}
