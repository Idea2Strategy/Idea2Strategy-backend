package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.AccountLifecycleCommand;
import com.idea2strategy.backend.application.identity.AccountLifecycleRejectedException;
import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.LifecycleOidcStepUpService;
import com.idea2strategy.backend.application.identity.OidcIdTokenVerificationException;
import com.idea2strategy.backend.application.identity.OidcStepUpChallengeService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
@ConditionalOnBean({LifecycleOidcStepUpService.class, OidcStepUpChallengeService.class})
public class IdentityOidcReactivationController {
    private final OidcStepUpChallengeService challenges;
    private final LifecycleOidcStepUpService stepUp;
    private final AccountLifecycleService lifecycle;

    public IdentityOidcReactivationController(
            OidcStepUpChallengeService challenges,
            LifecycleOidcStepUpService stepUp,
            AccountLifecycleService lifecycle) {
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.stepUp = Objects.requireNonNull(stepUp, "stepUp");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @PostMapping("/oidc-step-up-challenges")
    public ChallengeResponse issue(@RequestBody ChallengeRequest request) {
        var issued = challenges.issue(request.providerCode());
        return new ChallengeResponse(issued.id(), issued.providerCode(), issued.nonce(), issued.expiresAt());
    }

    @PostMapping("/reactivations/oidc")
    public IdentityAccountLifecycleController.LifecycleResponse reactivate(
            @RequestBody OidcReactivationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID() : UUID.fromString(correlationId);
        try {
            var authenticated = stepUp.authenticate(
                    request.providerCode(), request.idToken(), request.challengeId(), correlation);
            return IdentityAccountLifecycleController.LifecycleResponse.from(lifecycle.reactivate(
                    new AccountLifecycleCommand(
                            authenticated.accountId(),
                            idempotencyKey,
                            IdentityAccountLifecycleController.reactivationRequestHash(
                                    authenticated.accountId(), "OIDC",
                                    authenticated.proof().providerCode(), request.challengeId(),
                                    request.acceptedPolicyDocumentIds()),
                            correlation,
                            authenticated.proof(),
                            request.acceptedPolicyDocumentIds())));
        } catch (AuthenticationRejectedException | OidcIdTokenVerificationException exception) {
            throw new LifecycleRequestRejectedException("STEP_UP_REQUIRED", correlation);
        } catch (AccountLifecycleRejectedException exception) {
            throw new LifecycleRequestRejectedException(exception.code(), correlation);
        }
    }

    public record ChallengeRequest(String providerCode) {
        public ChallengeRequest { Objects.requireNonNull(providerCode, "providerCode"); }
    }

    public record ChallengeResponse(UUID id, String providerCode, String nonce, java.time.Instant expiresAt) {
        @Override public String toString() {
            return "ChallengeResponse[id=" + id + ",providerCode=" + providerCode
                    + ",nonce=REDACTED,expiresAt=" + expiresAt + "]";
        }
    }

    public record OidcReactivationRequest(
            String providerCode,
            String idToken,
            UUID challengeId,
            java.util.Set<UUID> acceptedPolicyDocumentIds) {
        public OidcReactivationRequest {
            Objects.requireNonNull(providerCode, "providerCode");
            Objects.requireNonNull(idToken, "idToken");
            Objects.requireNonNull(challengeId, "challengeId");
            acceptedPolicyDocumentIds = acceptedPolicyDocumentIds == null
                    ? java.util.Set.of() : java.util.Set.copyOf(acceptedPolicyDocumentIds);
        }
        public OidcReactivationRequest(String providerCode, String idToken, UUID challengeId) {
            this(providerCode, idToken, challengeId, java.util.Set.of());
        }
        @Override public String toString() {
            return "OidcReactivationRequest[providerCode=" + providerCode
                    + ",idToken=REDACTED,challengeId=" + challengeId + "]";
        }
    }
}
