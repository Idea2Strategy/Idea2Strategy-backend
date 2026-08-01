package com.idea2strategy.backend.application.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import com.idea2strategy.backend.domain.competition.LiveRoomRules;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

public final class UserCompetitionRoomCreationService {
    private final CompetitionRoomCommandPort commandPort;
    private final ScoringTemplateCatalogService scoringCatalog;
    private final CurrentPrincipal principal;
    private final Clock clock;
    private final Supplier<UUID> roomIdSupplier;
    private final ObjectMapper objectMapper;

    public UserCompetitionRoomCreationService(
            CompetitionRoomCommandPort commandPort,
            ScoringTemplateCatalogService scoringCatalog,
            CurrentPrincipal principal,
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

    public CompetitionRoom create(CreateUserLiveRoomCommand command) {
        Objects.requireNonNull(command, "command");
        var scoringSelection = scoringCatalog.select(
                command.scoringTemplateVersionId(), command.scoringAdjustments());
        var room = CompetitionRoom.userLive(
                roomIdSupplier.get(),
                principal.accountId(),
                command.name(),
                command.accessType(),
                scoringSelection.template().id(),
                command.initialCashAmount(),
                command.botParticipationLimit(),
                command.perAccountBotLimit(),
                scoringParameters(scoringSelection.adjustments()),
                command.feePolicyId(),
                command.buyingPowerBufferPolicyId(),
                new LiveRoomRules(
                        command.stoppedBotSlotPolicy(),
                        command.minimumOperationSeconds(),
                        command.minimumFillCount()),
                command.schedule(),
                clock.instant());
        commandPort.save(room);
        return room;
    }

    private String scoringParameters(java.util.Map<String, java.math.BigDecimal> adjustments) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(adjustments));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize validated scoring adjustments", exception);
        }
    }
}
