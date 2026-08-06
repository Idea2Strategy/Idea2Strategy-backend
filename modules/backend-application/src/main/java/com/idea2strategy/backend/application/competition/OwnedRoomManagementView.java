package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record OwnedRoomManagementView(
        UUID roomId,
        String name,
        String accessType,
        String status,
        Instant createdAt,
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
        String timezoneName,
        List<Invitation> invitations,
        List<Participation> participations) {
    public OwnedRoomManagementView {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(accessType, "accessType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(scoringTemplateVersionId, "scoringTemplateVersionId");
        scoringAdjustments = Map.copyOf(Objects.requireNonNull(scoringAdjustments, "scoringAdjustments"));
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        Objects.requireNonNull(stoppedBotSlotPolicy, "stoppedBotSlotPolicy");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        Objects.requireNonNull(buyingPowerBufferPolicyId, "buyingPowerBufferPolicyId");
        Objects.requireNonNull(recruitmentOpensAt, "recruitmentOpensAt");
        Objects.requireNonNull(participationOpensAt, "participationOpensAt");
        Objects.requireNonNull(evaluationStartsAt, "evaluationStartsAt");
        Objects.requireNonNull(participationClosesAt, "participationClosesAt");
        Objects.requireNonNull(evaluationEndsAt, "evaluationEndsAt");
        Objects.requireNonNull(finalizationDeadlineAt, "finalizationDeadlineAt");
        Objects.requireNonNull(timezoneName, "timezoneName");
        invitations = List.copyOf(Objects.requireNonNull(invitations, "invitations"));
        participations = List.copyOf(Objects.requireNonNull(participations, "participations"));
    }

    public record Invitation(
            UUID invitationId,
            String credentialType,
            Instant issuedAt,
            Instant expiresAt,
            Instant revokedAt,
            String revocationReasonCode) {}

    public record Participation(
            UUID participationId,
            UUID botId,
            String anonymousAlias,
            String status,
            Instant joinedAt) {}
}
