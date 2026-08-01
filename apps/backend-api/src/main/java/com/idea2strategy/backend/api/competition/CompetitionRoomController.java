package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.CreateUserLiveRoomCommand;
import com.idea2strategy.backend.application.competition.UserCompetitionRoomCreationService;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import com.idea2strategy.backend.domain.competition.RoomStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms")
@ConditionalOnBean(UserCompetitionRoomCreationService.class)
public class CompetitionRoomController {
    private final UserCompetitionRoomCreationService creationService;

    public CompetitionRoomController(UserCompetitionRoomCreationService creationService) {
        this.creationService = creationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomCreationResponse create(@RequestBody CreateRoomRequest request) {
        var room = creationService.create(request.toCommand());
        return new RoomCreationResponse(room.id(), room.accessType(), room.status());
    }

    public record CreateRoomRequest(
            String name,
            RoomAccessType accessType,
            UUID scoringTemplateVersionId,
            Map<String, BigDecimal> scoringAdjustments,
            BigDecimal initialCashAmount,
            int botParticipationLimit,
            int perAccountBotLimit,
            String stoppedBotSlotPolicy,
            long minimumOperationSeconds,
            int minimumFillCount,
            UUID feePolicyId,
            UUID buyingPowerBufferPolicyId,
            Instant recruitmentOpensAt,
            Instant participationOpensAt,
            Instant evaluationStartsAt,
            Instant participationClosesAt,
            Instant evaluationEndsAt,
            Instant finalizationDeadlineAt,
            String timezoneName) {
        CreateUserLiveRoomCommand toCommand() {
            return new CreateUserLiveRoomCommand(
                    name,
                    accessType,
                    scoringTemplateVersionId,
                    scoringAdjustments,
                    initialCashAmount,
                    botParticipationLimit,
                    perAccountBotLimit,
                    stoppedBotSlotPolicy,
                    minimumOperationSeconds,
                    minimumFillCount,
                    feePolicyId,
                    buyingPowerBufferPolicyId,
                    new RoomSchedule(
                            recruitmentOpensAt,
                            participationOpensAt,
                            evaluationStartsAt,
                            participationClosesAt,
                            evaluationEndsAt,
                            finalizationDeadlineAt,
                            timezoneName));
        }
    }

    public record RoomCreationResponse(UUID id, RoomAccessType accessType, RoomStatus status) {}
}
