package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class OidcIdentityLinkingService {
    private final OidcIdentityQueryPort queryPort;
    private final OidcIdentityCommandPort commandPort;
    private final OidcSubjectProtector subjectProtector;
    private final Clock clock;

    public OidcIdentityLinkingService(
            OidcIdentityQueryPort queryPort,
            OidcIdentityCommandPort commandPort,
            OidcSubjectProtector subjectProtector,
            Clock clock) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.subjectProtector = Objects.requireNonNull(subjectProtector, "subjectProtector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public UUID start(StartOidcLinkCommand command) {
        Objects.requireNonNull(command, "command");
        String providerCode = command.providerCode().trim().toUpperCase(Locale.ROOT);
        OidcProvider provider = queryPort.findProvider(providerCode)
                .filter(candidate -> candidate.active()
                        && candidate.code().equals(providerCode)
                        && candidate.issuer().equals(command.issuer()))
                .orElseThrow(() -> new AuthenticationRejectedException("OIDC provider is not trusted"));
        ProtectedOidcSubject protectedSubject = subjectProtector.protect(new VerifiedOidcPrincipal(
                providerCode, command.issuer(), command.subject(), command.email()));
        if (queryPort.subjectExists(provider.id(), protectedSubject.hmac())) {
            throw new AuthenticationRejectedException("OIDC identity is already linked");
        }

        UUID pendingId = UUID.randomUUID();
        commandPort.createPendingLink(new PendingOidcLink(
                pendingId,
                command.accountId(),
                command.reauthenticatedLoginIdentityId(),
                provider.id(),
                protectedSubject.hmac(),
                protectedSubject.keyVersion(),
                command.correlationId(),
                clock.instant(),
                protectedSubject.comparisonFingerprints()));
        return pendingId;
    }

    public long activate(ConfirmOidcLinkCommand command) {
        Objects.requireNonNull(command, "command");
        String providerCode = command.providerCode().trim().toUpperCase(Locale.ROOT);
        OidcProvider provider = queryPort.findProvider(providerCode)
                .filter(candidate -> candidate.active()
                        && candidate.code().equals(providerCode)
                        && candidate.issuer().equals(command.issuer()))
                .orElseThrow(() -> new AuthenticationRejectedException("OIDC provider is not trusted"));
        ProtectedOidcSubject protectedSubject = subjectProtector.protect(new VerifiedOidcPrincipal(
                providerCode, command.issuer(), command.subject(), command.email()));
        return commandPort.activatePendingLink(new ActivateOidcLink(
                command.accountId(),
                command.reauthenticatedLoginIdentityId(),
                command.pendingLoginIdentityId(),
                provider.id(),
                protectedSubject.hmac(),
                command.correlationId(),
                clock.instant(),
                protectedSubject.comparisonFingerprints()));
    }
}
