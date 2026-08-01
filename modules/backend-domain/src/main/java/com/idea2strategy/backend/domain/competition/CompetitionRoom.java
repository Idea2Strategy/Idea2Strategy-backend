package com.idea2strategy.backend.domain.competition;

import java.math.BigDecimal;
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
        if (botParticipationLimit <= 0 || perAccountBotLimit <= 0
                || perAccountBotLimit > botParticipationLimit) {
            throw new IllegalArgumentException("bot participation limits are invalid");
        }
        if (slippageRateBps != 5) {
            throw new IllegalArgumentException("slippageRateBps must be 5");
        }
        if (organizerType == RoomOrganizerType.USER
                && (creatorAccountId == null || createdByOperatorId != null)) {
            throw new IllegalArgumentException("User rooms require exactly one creator account");
        }
        if (organizerType == RoomOrganizerType.PLATFORM
                && (creatorAccountId != null || createdByOperatorId == null)) {
            throw new IllegalArgumentException("Platform rooms require exactly one operator");
        }
    }

    public static CompetitionRoom publicLive(
            UUID id,
            UUID creatorAccountId,
            String name,
            UUID scoringTemplateVersionId,
            BigDecimal initialCashAmount,
            int botParticipationLimit,
            int perAccountBotLimit,
            UUID feePolicyId,
            UUID buyingPowerBufferPolicyId,
            RoomSchedule schedule,
            Instant createdAt) {
        return new CompetitionRoom(
                id,
                CompetitionType.LIVE_PAPER,
                RoomOrganizerType.USER,
                creatorAccountId,
                null,
                name,
                RoomAccessType.PUBLIC,
                RoomStatus.DRAFT,
                scoringTemplateVersionId,
                initialCashAmount,
                "USD",
                botParticipationLimit,
                perAccountBotLimit,
                "{}",
                "{\"market\":\"US\"}",
                "{}",
                feePolicyId,
                5,
                buyingPowerBufferPolicyId,
                "v1",
                "room-rules-" + id,
                createdAt,
                schedule,
                createdAt);
    }
}
