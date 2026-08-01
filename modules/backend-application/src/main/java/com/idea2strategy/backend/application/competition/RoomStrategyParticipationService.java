package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommandService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleasePreparationCommand;
import com.idea2strategy.backend.application.strategy.StrategyValidationRunQueryPort;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class RoomStrategyParticipationService {
    private final RoomParticipationAdmissionService admissionService;
    private final RoomStrategyBotProvisioningPort provisioningPort;
    private final ImmutableStrategyReleaseCommandService releaseService;
    private final BasicStrategyCatalogQueryService catalogService;
    private final StrategyValidationRunQueryPort validationPort;
    private final CurrentPrincipal principal;
    private final Supplier<UUID> botIdSupplier;

    public RoomStrategyParticipationService(
            RoomParticipationAdmissionService admissionService,
            RoomStrategyBotProvisioningPort provisioningPort,
            ImmutableStrategyReleaseCommandService releaseService,
            BasicStrategyCatalogQueryService catalogService,
            StrategyValidationRunQueryPort validationPort,
            CurrentPrincipal principal,
            Supplier<UUID> botIdSupplier) {
        this.admissionService = Objects.requireNonNull(admissionService, "admissionService");
        this.provisioningPort = Objects.requireNonNull(provisioningPort, "provisioningPort");
        this.releaseService = Objects.requireNonNull(releaseService, "releaseService");
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService");
        this.validationPort = Objects.requireNonNull(validationPort, "validationPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.botIdSupplier = Objects.requireNonNull(botIdSupplier, "botIdSupplier");
    }

    public RoomParticipationAdmission join(JoinRoomWithStrategyCommand command) {
        Objects.requireNonNull(command, "command");
        var catalog = catalogService.getPublished(
                command.languageVersion(), command.schemaVersion(), command.catalogVersion());
        return admissionService.admit(command.roomId(), command.anonymousAlias(), context -> {
            var preparation = new ImmutableStrategyReleasePreparationCommand(
                    botIdSupplier.get(),
                    context.launchRules().initialCashAmount(),
                    command.budgetCapBps(),
                    command.brokerRulesVersion(),
                    command.accountingRulesVersion(),
                    context.launchRules().precisionRulesVersion(),
                    context.launchRules().feePolicyId(),
                    context.launchRules().buyingPowerBufferPolicyId(),
                    command.candidateConflictPolicy());
            var release = releaseService.prepare(
                    command.validationRunId(), catalog, preparation, context.admittedAt());
            var validation = validationPort.findOwnedById(command.validationRunId(), principal.accountId())
                    .orElseThrow(() -> new NoSuchElementException("Strategy validation not found"));
            return provisioningPort.provision(
                    release,
                    validation.id(),
                    validation.requestedEditSequence(),
                    validation.semanticHash(),
                    context.executionEligibleFrom());
        });
    }
}
