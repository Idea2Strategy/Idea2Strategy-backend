package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record CreateOfficialBacktestRoomCommand(
        String name,
        RoomAccessType accessType,
        UUID scoringTemplateVersionId,
        BigDecimal initialCashAmount,
        int botParticipationLimit,
        int perAccountBotLimit,
        UUID feePolicyId,
        UUID buyingPowerBufferPolicyId,
        Map<String, Object> eligibilityCriteria,
        Map<String, Object> marketScope,
        String precisionRulesVersion,
        RoomSchedule schedule,
        String planVersion,
        String planHash,
        String commitmentHash,
        String commitmentNonceCiphertext,
        int nonceKeyVersion,
        List<Period> periods) {
    public CreateOfficialBacktestRoomCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(accessType, "accessType");
        Objects.requireNonNull(scoringTemplateVersionId, "scoringTemplateVersionId");
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        Objects.requireNonNull(buyingPowerBufferPolicyId, "buyingPowerBufferPolicyId");
        eligibilityCriteria = Map.copyOf(Objects.requireNonNull(eligibilityCriteria, "eligibilityCriteria"));
        marketScope = Map.copyOf(Objects.requireNonNull(marketScope, "marketScope"));
        Objects.requireNonNull(precisionRulesVersion, "precisionRulesVersion");
        Objects.requireNonNull(schedule, "schedule");
        periods = List.copyOf(Objects.requireNonNull(periods, "periods"));
    }

    public record Period(
            LocalDate evaluationStart,
            LocalDate evaluationEnd,
            BigDecimal importanceWeight,
            String inputSetHash,
            List<BacktestEvaluationPlanDefinition.Dataset> datasets,
            List<BacktestEvaluationPlanDefinition.FeatureMaterialization> featureMaterializations) {
        public Period {
            Objects.requireNonNull(evaluationStart, "evaluationStart");
            Objects.requireNonNull(evaluationEnd, "evaluationEnd");
            Objects.requireNonNull(importanceWeight, "importanceWeight");
            datasets = List.copyOf(Objects.requireNonNull(datasets, "datasets"));
            featureMaterializations = List.copyOf(
                    Objects.requireNonNull(featureMaterializations, "featureMaterializations"));
        }
    }
}
