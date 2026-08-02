package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.CurrentPolicyDecision;
import com.idea2strategy.backend.application.identity.PolicyConsentService;
import com.idea2strategy.backend.application.identity.RecordConsentDecision;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import com.idea2strategy.backend.domain.identity.AccountConsent;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@ConditionalOnBean({PolicyConsentService.class, SessionManagementService.class, HmacSessionTokens.class})
public class IdentityPolicyController {
    private static final String BEARER_PREFIX = "Bearer ";

    private final PolicyConsentService policies;
    private final SessionManagementService sessions;
    private final HmacSessionTokens tokens;

    public IdentityPolicyController(
            PolicyConsentService policies,
            SessionManagementService sessions,
            HmacSessionTokens tokens) {
        this.policies = policies;
        this.sessions = sessions;
        this.tokens = tokens;
    }

    @GetMapping("/current")
    public List<CurrentPolicyDecision> current(
            @RequestParam String language,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlation(correlationId);
        var principal = sessions.authenticate(digest(authorization), correlation);
        return policies.currentPolicies(principal.accountId(), language);
    }

    @GetMapping("/{policyDocumentId}/consents")
    public List<AccountConsent> history(
            @PathVariable UUID policyDocumentId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlation(correlationId);
        var principal = sessions.authenticate(digest(authorization), correlation);
        return policies.history(principal.accountId(), policyDocumentId);
    }

    @PostMapping("/{policyDocumentId}/consents")
    public AccountConsent decide(
            @PathVariable UUID policyDocumentId,
            @RequestBody ConsentDecisionRequest request,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlation(correlationId);
        var principal = sessions.authenticate(digest(authorization), correlation);
        return policies.decide(
                principal.accountId(),
                new RecordConsentDecision(policyDocumentId, request.decision(), correlation));
    }

    private String digest(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new IllegalArgumentException("Authorization must use a Bearer session token");
        }
        String rawToken = authorization.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isEmpty()) {
            throw new IllegalArgumentException("Bearer session token is required");
        }
        return tokens.digest(rawToken);
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }

    public record ConsentDecisionRequest(String decision) {}
}
