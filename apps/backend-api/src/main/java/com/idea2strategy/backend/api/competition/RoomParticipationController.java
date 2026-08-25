package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.JoinRoomWithStrategyCommand;
import com.idea2strategy.backend.application.competition.RoomStrategyParticipationService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms/{roomId}/participations")
@ConditionalOnBean(RoomStrategyParticipationService.class)
public class RoomParticipationController {
    private final RoomStrategyParticipationService participationService;

    public RoomParticipationController(RoomStrategyParticipationService participationService) {
        this.participationService = participationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipationResponse join(
            @PathVariable UUID roomId,
            @RequestBody JoinRoomRequest request) {
        var participation = participationService.join(request.toCommand(roomId));
        return new ParticipationResponse(
                participation.participationId(),
                participation.roomId(),
                participation.botId(),
                participation.anonymousAlias(),
                participation.joinedAt());
    }

    public record JoinRoomRequest(
            UUID validationRunId,
            String anonymousAlias,
            String languageVersion,
            String schemaVersion,
            String catalogVersion,
            int budgetCapBps) {
        JoinRoomWithStrategyCommand toCommand(UUID roomId) {
            return new JoinRoomWithStrategyCommand(
                    roomId,
                    validationRunId,
                    anonymousAlias,
                    languageVersion,
                    schemaVersion,
                    catalogVersion,
                    budgetCapBps);
        }
    }

    public record ParticipationResponse(
            UUID id, UUID roomId, UUID botId, String anonymousAlias, Instant joinedAt) {}
}
