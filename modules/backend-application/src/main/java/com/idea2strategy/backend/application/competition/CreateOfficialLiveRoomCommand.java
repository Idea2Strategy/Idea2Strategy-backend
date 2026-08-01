package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record CreateOfficialLiveRoomCommand(
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
        RoomSchedule schedule) {
    public CreateOfficialLiveRoomCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(accessType, "accessType");
        Objects.requireNonNull(scoringTemplateVersionId, "scoringTemplateVersionId");
        scoringAdjustments = Map.copyOf(Objects.requireNonNull(scoringAdjustments, "scoringAdjustments"));
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        Objects.requireNonNull(stoppedBotSlotPolicy, "stoppedBotSlotPolicy");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        Objects.requireNonNull(buyingPowerBufferPolicyId, "buyingPowerBufferPolicyId");
        eligibilityCriteria = Map.copyOf(Objects.requireNonNull(eligibilityCriteria, "eligibilityCriteria"));
        marketScope = Map.copyOf(Objects.requireNonNull(marketScope, "marketScope"));
        Objects.requireNonNull(precisionRulesVersion, "precisionRulesVersion");
        Objects.requireNonNull(schedule, "schedule");
    }
}
