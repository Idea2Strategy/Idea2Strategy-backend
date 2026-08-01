package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class RoomParticipationAdmissionService {
    private final RoomParticipationAdmissionPort admissionPort;
    private final CurrentPrincipal principal;
    private final Clock clock;
    private final Supplier<UUID> participationIdSupplier;
    private final Supplier<UUID> eventIdSupplier;

    public RoomParticipationAdmissionService(
            RoomParticipationAdmissionPort admissionPort,
            CurrentPrincipal principal,
            Clock clock,
            Supplier<UUID> participationIdSupplier,
            Supplier<UUID> eventIdSupplier) {
        this.admissionPort = Objects.requireNonNull(admissionPort, "admissionPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.participationIdSupplier = Objects.requireNonNull(participationIdSupplier, "participationIdSupplier");
        this.eventIdSupplier = Objects.requireNonNull(eventIdSupplier, "eventIdSupplier");
    }

    public RoomParticipationAdmission admit(
            UUID roomId, String anonymousAlias, RoomBotProvisioningAction provisioningAction) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(provisioningAction, "provisioningAction");
        String normalizedAlias = normalizeAlias(anonymousAlias);
        var request = new RoomParticipationAdmissionRequest(
                participationIdSupplier.get(),
                eventIdSupplier.get(),
                roomId,
                principal.accountId(),
                normalizedAlias,
                clock.instant());
        var outcome = admissionPort.admit(request, provisioningAction);
        if (!outcome.accepted()) {
            throw new RoomParticipationAdmissionException(outcome.failure());
        }
        return outcome.admission();
    }

    private static String normalizeAlias(String anonymousAlias) {
        if (anonymousAlias == null || anonymousAlias.isBlank()) {
            throw new IllegalArgumentException("anonymousAlias is required");
        }
        String normalized = anonymousAlias.trim();
        if (normalized.length() > 80 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("anonymousAlias must contain at most 80 visible characters");
        }
        return normalized;
    }
}
