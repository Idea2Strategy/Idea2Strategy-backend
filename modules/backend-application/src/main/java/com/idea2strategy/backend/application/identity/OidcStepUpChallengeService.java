package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class OidcStepUpChallengeService {
    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

    private final OidcIdentityQueryPort identities;
    private final OidcStepUpChallengePort challenges;
    private final OidcStepUpNonceSupport nonces;
    private final Clock clock;
    private final Duration lifetime;

    public OidcStepUpChallengeService(
            OidcIdentityQueryPort identities,
            OidcStepUpChallengePort challenges,
            OidcStepUpNonceSupport nonces,
            Clock clock) {
        this(identities, challenges, nonces, clock, DEFAULT_LIFETIME);
    }

    public OidcStepUpChallengeService(
            OidcIdentityQueryPort identities,
            OidcStepUpChallengePort challenges,
            OidcStepUpNonceSupport nonces,
            Clock clock,
            Duration lifetime) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("lifetime must be positive");
        }
    }

    public OidcStepUpChallenge issue(String requestedProviderCode) {
        String providerCode = Objects.requireNonNull(requestedProviderCode, "providerCode")
                .trim().toUpperCase(Locale.ROOT);
        OidcProvider provider = identities.findProvider(providerCode)
                .filter(candidate -> candidate.active() && candidate.code().equals(providerCode))
                .orElseThrow(() -> new AuthenticationRejectedException("OIDC provider is not trusted"));
        IssuedOidcStepUpNonce issued = nonces.issue();
        UUID challengeId = UUID.randomUUID();
        var requestedAt = clock.instant();
        var expiresAt = requestedAt.plus(lifetime);
        challenges.create(
                challengeId,
                provider.id(),
                issued.digest(),
                issued.keyVersion(),
                requestedAt,
                expiresAt);
        return new OidcStepUpChallenge(challengeId, providerCode, issued.rawNonce(), expiresAt);
    }
}
