package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.LiveRoomRules;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RoomConfigurationUpdate(
        UUID roomId,
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
        String rulesHash,
        LiveRoomRules liveRules,
        RoomSchedule schedule,
        Instant observedAt) {}
