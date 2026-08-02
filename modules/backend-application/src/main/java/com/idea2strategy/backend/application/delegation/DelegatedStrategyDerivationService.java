package com.idea2strategy.backend.application.delegation;

import com.idea2strategy.backend.application.strategy.DelegatedStrategyAuthorizationPort;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyScope;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class DelegatedStrategyDerivationService {
    private final DelegatedStrategyCapabilityPort capabilities;
    private final DelegatedStrategyAuthorizationPort targets;
    private final DelegatedStrategyDerivationCommandPort commands;
    private final Clock clock;

    public DelegatedStrategyDerivationService(
            DelegatedStrategyCapabilityPort capabilities,
            DelegatedStrategyAuthorizationPort targets,
            DelegatedStrategyDerivationCommandPort commands,
            Clock clock) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DelegatedStrategyDerivationResult record(DelegatedStrategyDerivationCommand command) {
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        DelegatedStrategyScope scope = command.derivationType() == DelegatedStrategyDerivationType.CREATE
                ? DelegatedStrategyScope.STRATEGY_CREATE
                : DelegatedStrategyScope.STRATEGY_COPY;
        capabilities.requireAuthorized(
                command.editor(), scope, command.expectedAuthorizationVersion(), now);
        if (command.derivationType() == DelegatedStrategyDerivationType.COPY) {
            // The target-specific port accepts only explicit targets for COPY; derived results
            // are deliberately excluded by the persistence contract and database foreign key.
            targets.requireAuthorized(command.editor(), command.sourceStrategyId(), scope, now);
        }
        return commands.executeAtomically(command, now, () -> new DelegatedStrategyDerivationMutation(
                command.derivationType(),
                command.editor().authorizationId(),
                command.editor().credentialId(),
                command.expectedAuthorizationVersion(),
                command.sourceStrategyId(),
                command.resultStrategyId(),
                command.editor().accountId(),
                command.resultStrategyAccessEpoch(),
                command.correlationId(),
                command.idempotencyKey(),
                command.requestHash(),
                now));
    }
}
