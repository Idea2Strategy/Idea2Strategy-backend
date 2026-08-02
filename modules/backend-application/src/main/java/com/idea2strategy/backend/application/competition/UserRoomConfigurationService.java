package com.idea2strategy.backend.application.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import com.idea2strategy.backend.domain.competition.LiveRoomRules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public final class UserRoomConfigurationService {
    private final RoomConfigurationPort port;
    private final ScoringTemplateCatalogService scoringCatalog;
    private final CurrentPrincipal principal;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public UserRoomConfigurationService(
            RoomConfigurationPort port,
            ScoringTemplateCatalogService scoringCatalog,
            CurrentPrincipal principal,
            Clock clock,
            ObjectMapper objectMapper) {
        this.port = Objects.requireNonNull(port, "port");
        this.scoringCatalog = Objects.requireNonNull(scoringCatalog, "scoringCatalog");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void update(UUID roomId, UpdateUserLiveRoomCommand command) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(command, "command");
        var scoringSelection = scoringCatalog.select(
                command.scoringTemplateVersionId(), command.scoringAdjustments());
        String scoringParameters = json(new TreeMap<>(scoringSelection.adjustments()));
        var liveRules = new LiveRoomRules(
                command.stoppedBotSlotPolicy(),
                command.minimumOperationSeconds(),
                command.minimumFillCount());
        // Reuse the domain constructor to enforce the same capital, capacity and LIVE schedule invariants as creation.
        CompetitionRoom candidate = CompetitionRoom.userLive(
                roomId,
                principal.accountId(),
                command.name(),
                command.accessType(),
                scoringSelection.template().id(),
                command.initialCashAmount(),
                command.botParticipationLimit(),
                command.perAccountBotLimit(),
                scoringParameters,
                command.feePolicyId(),
                command.buyingPowerBufferPolicyId(),
                liveRules,
                command.schedule(),
                clock.instant());
        String rulesHash = hash(candidate);
        var update = new RoomConfigurationUpdate(
                roomId,
                principal.accountId(),
                candidate.name(),
                candidate.accessType(),
                candidate.scoringTemplateVersionId(),
                candidate.initialCashAmount(),
                candidate.botParticipationLimit(),
                candidate.perAccountBotLimit(),
                candidate.scoringParameters(),
                candidate.feePolicyId(),
                candidate.buyingPowerBufferPolicyId(),
                rulesHash,
                candidate.liveRules(),
                candidate.schedule(),
                clock.instant());
        switch (port.update(update)) {
            case UPDATED -> { }
            case NOT_FOUND_OR_NOT_OWNED -> throw new RoomConfigurationAccessException();
            case ACCESS_TYPE_IMMUTABLE -> throw new RoomConfigurationConflictException(
                    "Room access type is immutable after creation");
            case RECRUITMENT_LOCKED -> throw new RoomConfigurationConflictException(
                    "Room configuration is locked once recruitment starts");
        }
    }

    private String hash(CompetitionRoom room) {
        var live = room.liveRules();
        var schedule = room.schedule();
        String canonical = String.join(
                "|",
                room.accessType().name(),
                room.scoringTemplateVersionId().toString(),
                room.initialCashAmount().stripTrailingZeros().toPlainString(),
                Integer.toString(room.botParticipationLimit()),
                Integer.toString(room.perAccountBotLimit()),
                room.marketScopeDocument(),
                room.scoringParameters(),
                room.feePolicyId().toString(),
                Integer.toString(room.slippageRateBps()),
                room.buyingPowerBufferPolicyId().toString(),
                room.precisionRulesVersion(),
                live.stoppedBotSlotPolicy(),
                Long.toString(live.minimumOperationSeconds()),
                Integer.toString(live.minimumFillCount()),
                schedule.recruitmentOpensAt().toString(),
                schedule.participationOpensAt().toString(),
                schedule.evaluationStartsAt().toString(),
                schedule.participationClosesAt().toString(),
                schedule.evaluationEndsAt().toString(),
                schedule.finalizationDeadlineAt().toString(),
                schedule.timezoneName());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize room configuration", exception);
        }
    }
}
