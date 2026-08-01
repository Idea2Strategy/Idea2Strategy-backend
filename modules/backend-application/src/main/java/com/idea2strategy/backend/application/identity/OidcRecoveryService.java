package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class OidcRecoveryService {
    private final OidcIdentityQueryPort queries;
    private final AccountRecoveryCommandPort commands;
    private final OidcSubjectProtector subjectProtector;
    private final Clock clock;

    public OidcRecoveryService(
            OidcIdentityQueryPort queries,
            AccountRecoveryCommandPort commands,
            OidcSubjectProtector subjectProtector,
            Clock clock) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.subjectProtector = Objects.requireNonNull(subjectProtector, "subjectProtector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public UUID verifyExistingLink(VerifiedOidcPrincipal principal, UUID correlationId) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(correlationId, "correlationId");
        String providerCode = principal.providerCode().trim().toUpperCase(Locale.ROOT);
        OidcProvider provider = queries.findProvider(providerCode)
                .filter(candidate -> candidate.active()
                        && candidate.code().equals(providerCode)
                        && candidate.issuer().equals(principal.issuer()))
                .orElseThrow(() -> new AuthenticationRejectedException("OIDC provider is not trusted"));
        var canonicalPrincipal = new VerifiedOidcPrincipal(
                providerCode, principal.issuer(), principal.subject(), principal.email());
        ProtectedOidcSubject subject = subjectProtector.protect(canonicalPrincipal);
        OidcLoginAccount account = queries.findActiveLogin(provider.id(), subject.hmac())
                .filter(candidate -> candidate.accountStatus() == AccountLifecycleStatus.ACTIVE
                        && candidate.loginIdentityStatus() == LoginIdentityStatus.ACTIVE)
                .orElseThrow(() -> new AuthenticationRejectedException("OIDC identity is not linked"));
        commands.recordOidcRecoveryProof(
                account.accountId(), account.loginIdentityId(), correlationId, clock.instant());
        return account.accountId();
    }
}
