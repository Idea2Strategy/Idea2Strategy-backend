package com.idea2strategy.backend.application.delegation;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class DelegatedAuthorizationService {
    private final DelegatedAuthorizationCommandPort commands;
    private final DelegatedCredentialMaterialPort credentials;
    private final Clock clock;
    private final Supplier<UUID> credentialIds;

    public DelegatedAuthorizationService(
            DelegatedAuthorizationCommandPort commands,
            DelegatedCredentialMaterialPort credentials,
            Clock clock,
            Supplier<UUID> credentialIds) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.credentialIds = Objects.requireNonNull(credentialIds, "credentialIds");
    }

    public DelegatedAuthorizationResult execute(DelegatedAuthorizationCommand command) {
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        var rawCredential = new AtomicReference<String>();
        DelegatedAuthorizationExecution execution = commands.executeAtomically(
                command, now, current -> decide(command, current, now, rawCredential));
        if (!execution.newlyApplied() || rawCredential.get() == null) {
            return execution.result();
        }
        return execution.result().withRawCredential(rawCredential.get());
    }

    private DelegatedAuthorizationMutation decide(
            DelegatedAuthorizationCommand command,
            Optional<DelegatedAuthorizationSnapshot> current,
            Instant now,
            AtomicReference<String> rawCredential) {
        return switch (command.commandType()) {
            case CREATE -> {
                if (current.isPresent()) {
                    throw new DelegatedAuthorizationConflictException();
                }
                yield grant(command, 1, null, null, now, rawCredential);
            }
            case REPLACE -> {
                DelegatedAuthorizationSnapshot predecessor = requireCurrent(command, current);
                if (predecessor.status() != DelegatedAuthorizationStatus.ACTIVE) {
                    throw new DelegatedAuthorizationConflictException();
                }
                yield grant(
                        command,
                        predecessor.authorizationVersion() + 1,
                        predecessor.authorizationId(),
                        now,
                        now,
                        rawCredential);
            }
            case REVOKE -> {
                DelegatedAuthorizationSnapshot active = requireCurrent(command, current);
                if (active.status() != DelegatedAuthorizationStatus.ACTIVE) {
                    throw new DelegatedAuthorizationConflictException();
                }
                yield new DelegatedAuthorizationMutation(
                        command.commandType(), active.authorizationId(), active.accountId(),
                        active.authorizationVersion(), null, DelegatedAuthorizationStatus.REVOKED,
                        active.authEpochAtGrant(), command.clientLabel(), command.disclosurePolicyDocumentId(),
                        command.scopes(), command.targetStrategyIds(), active.expiresAt(), null,
                        command.reasonCode(), null, null, null, now, command.correlationId());
            }
        };
    }

    private DelegatedAuthorizationSnapshot requireCurrent(
            DelegatedAuthorizationCommand command,
            Optional<DelegatedAuthorizationSnapshot> current) {
        DelegatedAuthorizationSnapshot snapshot = current.orElseThrow(DelegatedAuthorizationConflictException::new);
        UUID expectedId = command.commandType() == DelegatedAuthorizationCommandType.REPLACE
                ? command.replacesAuthorizationId()
                : command.authorizationId();
        if (!snapshot.authorizationId().equals(expectedId)
                || !snapshot.accountId().equals(command.accountId())
                || snapshot.authorizationVersion() != command.expectedAuthorizationVersion()) {
            throw new DelegatedAuthorizationConflictException();
        }
        return snapshot;
    }

    private DelegatedAuthorizationMutation grant(
            DelegatedAuthorizationCommand command,
            long version,
            UUID predecessorId,
            Instant predecessorRevokedAt,
            Instant now,
            AtomicReference<String> rawCredential) {
        DelegatedCredentialMaterial material = credentials.issue();
        rawCredential.set(material.rawValue());
        return new DelegatedAuthorizationMutation(
                command.commandType(), command.authorizationId(), command.accountId(), version,
                predecessorId, DelegatedAuthorizationStatus.ACTIVE, command.authEpochAtGrant(),
                command.clientLabel(), command.disclosurePolicyDocumentId(), command.scopes(),
                command.targetStrategyIds(), command.expiresAt(), predecessorRevokedAt, command.reasonCode(),
                credentialIds.get(), material.digest(), material.digestKeyVersion(), now, command.correlationId());
    }
}
