package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.CreateOfficialLiveRoomCommand;
import com.idea2strategy.backend.application.competition.OfficialCompetitionRoomCreationService;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomOrganizerType;
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
@RequestMapping("/api/v1/operations/competition/rooms")
@ConditionalOnBean(OfficialCompetitionRoomCreationService.class)
public class OfficialCompetitionRoomController {
    private final OfficialCompetitionRoomCreationService creationService;

    public OfficialCompetitionRoomController(OfficialCompetitionRoomCreationService creationService) {
        this.creationService = creationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OfficialRoomCreationResponse create(@RequestBody CreateOfficialRoomRequest request) {
        var room = creationService.create(request.toCommand());
        return new OfficialRoomCreationResponse(
                room.id(), room.organizerType(), room.accessType(), room.status(), room.lockedAt());
    }

    public record CreateOfficialRoomRequest(
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
            Map<String, Object> eligibilityCriteria,
            Map<String, Object> marketScope,
            String precisionRulesVersion,
            Instant recruitmentOpensAt,
            Instant participationOpensAt,
            Instant evaluationStartsAt,
            Instant participationClosesAt,
            Instant evaluationEndsAt,
            Instant finalizationDeadlineAt,
            String timezoneName) {
        CreateOfficialLiveRoomCommand toCommand() {
            return new CreateOfficialLiveRoomCommand(
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
                    eligibilityCriteria,
                    marketScope,
                    precisionRulesVersion,
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

    public record OfficialRoomCreationResponse(
            UUID id,
            RoomOrganizerType organizerType,
            RoomAccessType accessType,
            RoomStatus status,
            Instant lockedAt) {}
}
