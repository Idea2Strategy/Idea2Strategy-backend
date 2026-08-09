package com.idea2strategy.backend.domain.competition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CompetitionRoom(
        UUID id,
        CompetitionType competitionType,
        RoomOrganizerType organizerType,
        UUID creatorAccountId,
        UUID createdByOperatorId,
        String name,
        RoomAccessType accessType,
        RoomStatus status,
        UUID scoringTemplateVersionId,
        BigDecimal initialCashAmount,
        String currencyCode,
        int botParticipationLimit,
        int perAccountBotLimit,
        String eligibilityDocument,
        String marketScopeDocument,
        String scoringParameters,
        UUID feePolicyId,
        int slippageRateBps,
        UUID buyingPowerBufferPolicyId,
        String precisionRulesVersion,
        String rulesHash,
        Instant lockedAt,
        LiveRoomRules liveRules,
        RoomSchedule schedule,
        Instant createdAt) {

    public CompetitionRoom {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(competitionType, "competitionType");
        Objects.requireNonNull(organizerType, "organizerType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(accessType, "accessType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(scoringTemplateVersionId, "scoringTemplateVersionId");
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(eligibilityDocument, "eligibilityDocument");
        Objects.requireNonNull(marketScopeDocument, "marketScopeDocument");
        Objects.requireNonNull(scoringParameters, "scoringParameters");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        Objects.requireNonNull(buyingPowerBufferPolicyId, "buyingPowerBufferPolicyId");
        Objects.requireNonNull(precisionRulesVersion, "precisionRulesVersion");
        Objects.requireNonNull(rulesHash, "rulesHash");
        Objects.requireNonNull(lockedAt, "lockedAt");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(createdAt, "createdAt");
        if (name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("Room name must contain 1..120 characters");
        }
        if (initialCashAmount.signum() <= 0) {
            throw new IllegalArgumentException("initialCashAmount must be positive");
        }
        if (effectiveScale(initialCashAmount) > 8
                || initialCashAmount.setScale(8, RoundingMode.UNNECESSARY).precision() > 24) {
            throw new IllegalArgumentException("initialCashAmount exceeds supported precision");
        }
        if (botParticipationLimit <= 0 || perAccountBotLimit <= 0
                || perAccountBotLimit > botParticipationLimit) {
            throw new IllegalArgumentException("bot participation limits are invalid");
        }
        if (slippageRateBps != 5) {
            throw new IllegalArgumentException("slippageRateBps must be 5");
        }
        if (precisionRulesVersion.isBlank() || precisionRulesVersion.length() > 80) {
            throw new IllegalArgumentException("precisionRulesVersion must contain 1..80 characters");
        }
        if (!rulesHash.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("rulesHash must be a stable identifier");
        }
        if (organizerType == RoomOrganizerType.USER
                && (creatorAccountId == null || createdByOperatorId != null)) {
            throw new IllegalArgumentException("User rooms require exactly one creator account");
        }
        if (organizerType == RoomOrganizerType.PLATFORM
                && (creatorAccountId != null || createdByOperatorId == null)) {
            throw new IllegalArgumentException("Platform rooms require exactly one operator");
        }
        if (competitionType == CompetitionType.LIVE_PAPER) {
            Objects.requireNonNull(liveRules, "liveRules");
            if (schedule.participationClosesAt().isAfter(schedule.evaluationStartsAt())) {
                throw new IllegalArgumentException(
                        "LIVE_PAPER participation must close before evaluation starts");
            }
            long evaluationSeconds = Duration.between(
                    schedule.evaluationStartsAt(), schedule.evaluationEndsAt()).getSeconds();
            if (liveRules.minimumOperationSeconds() > evaluationSeconds) {
                throw new IllegalArgumentException(
                        "minimumOperationSeconds must not exceed the evaluation window");
            }
        } else if (liveRules != null) {
            throw new IllegalArgumentException("BACKTEST rooms must not contain live rules");
        }
    }

    public static CompetitionRoom userLive(
            UUID id,
            UUID creatorAccountId,
            String name,
            RoomAccessType accessType,
            UUID scoringTemplateVersionId,
            BigDecimal initialCashAmount,
            int botParticipationLimit,
            int perAccountBotLimit,
            String scoringParameters,
            UUID feePolicyId,
            UUID buyingPowerBufferPolicyId,
            LiveRoomRules liveRules,
            RoomSchedule schedule,
            Instant createdAt) {
        return new CompetitionRoom(
                id,
                CompetitionType.LIVE_PAPER,
                RoomOrganizerType.USER,
                creatorAccountId,
                null,
                name,
                accessType,
                RoomStatus.DRAFT,
                scoringTemplateVersionId,
                initialCashAmount,
                "USD",
                botParticipationLimit,
                perAccountBotLimit,
                "{}",
                "{\"market\":\"US\"}",
                scoringParameters,
                feePolicyId,
                5,
                buyingPowerBufferPolicyId,
                "v1",
                "room-rules-" + id,
                createdAt,
                liveRules,
                schedule,
                createdAt);
    }

    public static CompetitionRoom platformLive(
            UUID id,
            UUID operatorId,
            String name,
            RoomAccessType accessType,
            UUID scoringTemplateVersionId,
            BigDecimal initialCashAmount,
            int botParticipationLimit,
            int perAccountBotLimit,
            String eligibilityDocument,
            String marketScopeDocument,
            String scoringParameters,
            UUID feePolicyId,
            UUID buyingPowerBufferPolicyId,
            String precisionRulesVersion,
            String rulesHash,
            LiveRoomRules liveRules,
            RoomSchedule schedule,
            Instant createdAt) {
        return new CompetitionRoom(
                id,
                CompetitionType.LIVE_PAPER,
                RoomOrganizerType.PLATFORM,
                null,
                operatorId,
                name,
                accessType,
                RoomStatus.DRAFT,
                scoringTemplateVersionId,
                initialCashAmount,
                "USD",
                botParticipationLimit,
                perAccountBotLimit,
                eligibilityDocument,
                marketScopeDocument,
                scoringParameters,
                feePolicyId,
                5,
                buyingPowerBufferPolicyId,
                precisionRulesVersion,
                rulesHash,
                createdAt,
                liveRules,
                schedule,
                createdAt);
    }

    public static CompetitionRoom platformBacktest(
            UUID id,
            UUID operatorId,
            String name,
            RoomAccessType accessType,
            UUID scoringTemplateVersionId,
            BigDecimal initialCashAmount,
            int botParticipationLimit,
            int perAccountBotLimit,
            String eligibilityDocument,
            String marketScopeDocument,
            String scoringParameters,
            UUID feePolicyId,
            UUID buyingPowerBufferPolicyId,
            String precisionRulesVersion,
            String rulesHash,
            RoomSchedule schedule,
            Instant createdAt) {
        return new CompetitionRoom(
                id,
                CompetitionType.BACKTEST,
                RoomOrganizerType.PLATFORM,
                null,
                operatorId,
                name,
                accessType,
                RoomStatus.DRAFT,
                scoringTemplateVersionId,
                initialCashAmount,
                "USD",
                botParticipationLimit,
                perAccountBotLimit,
                eligibilityDocument,
                marketScopeDocument,
                scoringParameters,
                feePolicyId,
                5,
                buyingPowerBufferPolicyId,
                precisionRulesVersion,
                rulesHash,
                createdAt,
                null,
                schedule,
                createdAt);
    }

    private static int effectiveScale(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }
}
