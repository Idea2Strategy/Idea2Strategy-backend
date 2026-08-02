package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record UpdateUserLiveRoomCommand(
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
        RoomSchedule schedule) {
    public UpdateUserLiveRoomCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(accessType, "accessType");
        Objects.requireNonNull(scoringTemplateVersionId, "scoringTemplateVersionId");
        scoringAdjustments = Map.copyOf(Objects.requireNonNull(scoringAdjustments, "scoringAdjustments"));
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        Objects.requireNonNull(stoppedBotSlotPolicy, "stoppedBotSlotPolicy");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        Objects.requireNonNull(buyingPowerBufferPolicyId, "buyingPowerBufferPolicyId");
        Objects.requireNonNull(schedule, "schedule");
    }
}
