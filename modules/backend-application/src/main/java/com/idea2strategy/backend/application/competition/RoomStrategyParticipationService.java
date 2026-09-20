package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.BasicLaunchPolicy;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommandService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleasePreparationCommand;
import com.idea2strategy.backend.application.strategy.OfficialBacktestInputSelector;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalogQueryPort;
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
    private final StrategyReleaseInputCatalogQueryPort releaseInputs;
    private final CurrentPrincipal principal;
    private final Supplier<UUID> botIdSupplier;

    public RoomStrategyParticipationService(
            RoomParticipationAdmissionService admissionService,
            RoomStrategyBotProvisioningPort provisioningPort,
            ImmutableStrategyReleaseCommandService releaseService,
            BasicStrategyCatalogQueryService catalogService,
            StrategyValidationRunQueryPort validationPort,
            StrategyReleaseInputCatalogQueryPort releaseInputs,
            CurrentPrincipal principal,
            Supplier<UUID> botIdSupplier) {
        this.admissionService = Objects.requireNonNull(admissionService, "admissionService");
        this.provisioningPort = Objects.requireNonNull(provisioningPort, "provisioningPort");
        this.releaseService = Objects.requireNonNull(releaseService, "releaseService");
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService");
        this.validationPort = Objects.requireNonNull(validationPort, "validationPort");
        this.releaseInputs = Objects.requireNonNull(releaseInputs, "releaseInputs");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.botIdSupplier = Objects.requireNonNull(botIdSupplier, "botIdSupplier");
    }

    public RoomParticipationAdmission join(JoinRoomWithStrategyCommand command) {
        Objects.requireNonNull(command, "command");
        var catalog = catalogService.getPublished(
                command.languageVersion(), command.schemaVersion(), command.catalogVersion());
        return admissionService.admit(command.roomId(), command.anonymousAlias(), context -> {
            var policy = OfficialBacktestInputSelector.selectPolicy(
                    releaseInputs.findSelectableAt(context.admittedAt()));
            if (!policy.feePolicyId().equals(context.launchRules().feePolicyId())
                    || !policy.buyingPowerBufferPolicyId().equals(
                            context.launchRules().buyingPowerBufferPolicyId())
                    || !policy.precisionRulesVersion().equals(
                            context.launchRules().precisionRulesVersion())) {
                throw new IllegalStateException("Room execution policy is no longer available");
            }
            var preparation = new ImmutableStrategyReleasePreparationCommand(
                    botIdSupplier.get(),
                    context.launchRules().initialCashAmount(),
                    command.budgetCapBps(),
                    policy.brokerRulesVersion(),
                    policy.accountingRulesVersion(),
                    context.launchRules().precisionRulesVersion(),
                    context.launchRules().feePolicyId(),
                    context.launchRules().buyingPowerBufferPolicyId(),
                    BasicLaunchPolicy.CANDIDATE_CONFLICT_POLICY);
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
