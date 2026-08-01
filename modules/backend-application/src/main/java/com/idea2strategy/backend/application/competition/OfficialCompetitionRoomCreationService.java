package com.idea2strategy.backend.application.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.common.CurrentOperatorPrincipal;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import com.idea2strategy.backend.domain.competition.LiveRoomRules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

public final class OfficialCompetitionRoomCreationService {
    private final CompetitionRoomCommandPort commandPort;
    private final ScoringTemplateCatalogService scoringCatalog;
    private final CurrentOperatorPrincipal principal;
    private final Clock clock;
    private final Supplier<UUID> roomIdSupplier;
    private final ObjectMapper objectMapper;

    public OfficialCompetitionRoomCreationService(
            CompetitionRoomCommandPort commandPort,
            ScoringTemplateCatalogService scoringCatalog,
            CurrentOperatorPrincipal principal,
            Clock clock,
            Supplier<UUID> roomIdSupplier,
            ObjectMapper objectMapper) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.scoringCatalog = Objects.requireNonNull(scoringCatalog, "scoringCatalog");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.roomIdSupplier = Objects.requireNonNull(roomIdSupplier, "roomIdSupplier");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public CompetitionRoom create(CreateOfficialLiveRoomCommand command) {
        Objects.requireNonNull(command, "command");
        UUID operatorId = Objects.requireNonNull(principal.operatorId(), "operatorId")
                .orElseThrow(OperatorAuthorizationException::new);
        var scoringSelection = scoringCatalog.select(
                command.scoringTemplateVersionId(), command.scoringAdjustments());
        String eligibilityDocument = canonicalObject(command.eligibilityCriteria(), "eligibilityCriteria");
        String marketScopeDocument = canonicalObject(command.marketScope(), "marketScope");
        String scoringParameters = json(new TreeMap<>(scoringSelection.adjustments()));
        var liveRules = new LiveRoomRules(
                command.stoppedBotSlotPolicy(),
                command.minimumOperationSeconds(),
                command.minimumFillCount());
        var createdAt = clock.instant();
        String rulesHash = rulesHash(
                command,
                scoringSelection.template().version(),
                scoringSelection.template().rulesHash(),
                eligibilityDocument,
                marketScopeDocument,
                scoringParameters,
                liveRules);
        var room = CompetitionRoom.platformLive(
                roomIdSupplier.get(),
                operatorId,
                command.name(),
                command.accessType(),
                scoringSelection.template().id(),
                command.initialCashAmount(),
                command.botParticipationLimit(),
                command.perAccountBotLimit(),
                eligibilityDocument,
                marketScopeDocument,
                scoringParameters,
                command.feePolicyId(),
                command.buyingPowerBufferPolicyId(),
                command.precisionRulesVersion(),
                rulesHash,
                liveRules,
                command.schedule(),
                createdAt);
        commandPort.save(room);
        return room;
    }

    private String canonicalObject(java.util.Map<String, Object> document, String field) {
        if (document.isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty JSON object");
        }
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(document));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(field + " must contain valid JSON values", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize locked official room rules", exception);
        }
    }

    private static String rulesHash(
            CreateOfficialLiveRoomCommand command,
            String scoringTemplateVersion,
            String scoringTemplateRulesHash,
            String eligibilityDocument,
            String marketScopeDocument,
            String scoringParameters,
            LiveRoomRules liveRules) {
        String snapshot = String.join("\u0000",
                command.scoringTemplateVersionId().toString(),
                scoringTemplateVersion,
                scoringTemplateRulesHash,
                eligibilityDocument,
                marketScopeDocument,
                scoringParameters,
                command.initialCashAmount().toPlainString(),
                Integer.toString(command.botParticipationLimit()),
                Integer.toString(command.perAccountBotLimit()),
                command.feePolicyId().toString(),
                command.buyingPowerBufferPolicyId().toString(),
                command.precisionRulesVersion(),
                liveRules.stoppedBotSlotPolicy(),
                Long.toString(liveRules.minimumOperationSeconds()),
                Integer.toString(liveRules.minimumFillCount()),
                command.schedule().toString());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
