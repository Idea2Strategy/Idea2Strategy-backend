package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.UpdateUserLiveRoomCommand;
import com.idea2strategy.backend.application.competition.UserRoomConfigurationService;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/competition/rooms")
@ConditionalOnBean(UserRoomConfigurationService.class)
public class RoomConfigurationController {
    private final UserRoomConfigurationService service;

    public RoomConfigurationController(UserRoomConfigurationService service) {
        this.service = service;
    }

    @PutMapping("/{roomId}/configuration")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable UUID roomId, @RequestBody UpdateRoomConfigurationRequest request) {
        service.update(roomId, request.toCommand());
    }

    public record UpdateRoomConfigurationRequest(
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
        UpdateUserLiveRoomCommand toCommand() {
            return new UpdateUserLiveRoomCommand(
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
}
