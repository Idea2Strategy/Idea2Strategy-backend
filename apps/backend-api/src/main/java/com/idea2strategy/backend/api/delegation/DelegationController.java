package com.idea2strategy.backend.api.delegation;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommand;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationCommandType;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationResult;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationScope;
import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationService;
import com.idea2strategy.backend.application.delegation.DelegationGrantContextPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Grants and revokes the delegation an external tool edits under.
 *
 * <p>The raw credential is returned once, here, and never again: only its digest is stored. A
 * caller that loses it revokes and grants a new one.
 */
@RestController
@RequestMapping("/api/v1/delegations")
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
public class DelegationController {
    static final String DISCLOSURE_POLICY_CODE = "delegation.strategy-edit.disclosure";

    private final DelegatedAuthorizationService service;
    private final DelegationGrantContextPort grantContext;
    private final CurrentPrincipal principal;
    private final Clock clock;
    private final Duration defaultLifetime;

    public DelegationController(
            DelegatedAuthorizationService service,
            DelegationGrantContextPort grantContext,
            CurrentPrincipal principal,
            Clock clock,
            @Value("${delegation.default-lifetime:PT24H}") Duration defaultLifetime) {
        this.service = service;
        this.grantContext = grantContext;
        this.principal = principal;
        this.clock = clock;
        this.defaultLifetime = defaultLifetime;
    }

    @PostMapping
    public ResponseEntity<GrantResponse> create(@RequestBody CreateDelegationRequest request) {
        UUID accountId = principal.accountId();
        Set<DelegatedAuthorizationScope> scopes = scopes(request.scopes());
        Set<UUID> targets = targets(request.strategyIds());
        // A delegation with no expiry never stops working. The customer-visible disclosure
        // promises one, so an omitted value becomes the configured default rather than nothing.
        Instant expiresAt = request.expiresAt() != null
                ? request.expiresAt()
                : clock.instant().plus(defaultLifetime);
        if (!expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("A delegation must expire in the future");
        }

        UUID authorizationId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String requestHash = hash(accountId, scopes, targets, expiresAt, request.name());
        DelegatedAuthorizationResult result = service.execute(new DelegatedAuthorizationCommand(
                DelegatedAuthorizationCommandType.CREATE,
                accountId,
                authorizationId,
                null,
                0L,
                grantContext.currentAuthEpoch(accountId),
                requireName(request.name()),
                grantContext.currentDisclosurePolicyDocumentId(DISCLOSURE_POLICY_CODE),
                scopes,
                targets,
                expiresAt,
                "USER_REQUESTED",
                "delegation-create:" + requestHash,
                requestHash,
                correlationId));

        return ResponseEntity.status(HttpStatus.CREATED).body(new GrantResponse(
                result.authorizationId(),
                result.credentialId(),
                result.rawCredential().orElse(null),
                result.expiresAt(),
                scopes.stream().map(Enum::name).sorted().toList(),
                targets.stream().map(UUID::toString).sorted().toList()));
    }

    @DeleteMapping("/{authorizationId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID authorizationId) {
        UUID accountId = principal.accountId();
        String requestHash = hash(accountId, Set.of(), Set.of(authorizationId), null, "revoke");
        service.execute(new DelegatedAuthorizationCommand(
                DelegatedAuthorizationCommandType.REVOKE,
                accountId,
                authorizationId,
                null,
                1L,
                grantContext.currentAuthEpoch(accountId),
                "revoked",
                grantContext.currentDisclosurePolicyDocumentId(DISCLOSURE_POLICY_CODE),
                Set.of(),
                Set.of(),
                null,
                "USER_REQUESTED",
                "delegation-revoke:" + requestHash,
                requestHash,
                UUID.randomUUID()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Only the two Basic editing scopes are reachable here. The enum carries wider ones for other
     * flows, and an external tool must not be able to name them by spelling them in a request.
     */
    private static Set<DelegatedAuthorizationScope> scopes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("At least one delegation scope is required");
        }
        Set<DelegatedAuthorizationScope> allowed = Set.of(
                DelegatedAuthorizationScope.STRATEGY_EDIT, DelegatedAuthorizationScope.STRATEGY_VALIDATE);
        Set<DelegatedAuthorizationScope> scopes = requested.stream()
                .map(String::trim)
                .map(value -> {
                    try {
                        return DelegatedAuthorizationScope.valueOf(value);
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("Unknown delegation scope: " + value);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
        if (!allowed.containsAll(scopes)) {
            throw new IllegalArgumentException("Only Basic edit and validation scopes may be delegated");
        }
        return scopes;
    }

    /**
     * The authorization check requires a pinned target, so a delegation without one is granted,
     * returned, and then authorizes nothing. Refusing it here keeps that from looking like success.
     */
    private static Set<UUID> targets(List<UUID> strategyIds) {
        if (strategyIds == null || strategyIds.isEmpty()) {
            throw new IllegalArgumentException("A delegation must name at least one target strategy");
        }
        return Set.copyOf(strategyIds);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A delegation name is required");
        }
        return name.trim();
    }

    private static String hash(
            UUID accountId,
            Set<DelegatedAuthorizationScope> scopes,
            Set<UUID> targets,
            Instant expiresAt,
            String name) {
        TreeSet<String> parts = new TreeSet<>();
        scopes.forEach(scope -> parts.add("scope:" + scope.name()));
        targets.forEach(target -> parts.add("target:" + target));
        String canonical = accountId + "|" + name + "|" + expiresAt + "|" + String.join(",", parts);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record CreateDelegationRequest(
            String name, List<String> scopes, List<UUID> strategyIds, Instant expiresAt) {}

    public record GrantResponse(
            UUID authorizationId,
            UUID credentialId,
            String credential,
            Instant expiresAt,
            List<String> scopes,
            List<String> strategyIds) {
        @Override
        public String toString() {
            return "GrantResponse[credential=REDACTED]";
        }
    }
}
