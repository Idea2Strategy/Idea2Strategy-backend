package com.idea2strategy.backend.api.identity;

import com.idea2strategy.backend.application.identity.CurrentPolicyDecision;
import com.idea2strategy.backend.application.identity.PolicyConsentService;
import com.idea2strategy.backend.application.identity.RecordConsentDecision;
import com.idea2strategy.backend.application.identity.RefreshTokenService;
import com.idea2strategy.backend.domain.identity.AccountConsent;
import com.idea2strategy.backend.domain.identity.PolicyDocumentVersion;
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
@ConditionalOnBean({PolicyConsentService.class, RefreshTokenService.class, HmacRefreshTokenSecrets.class})
public class IdentityPolicyController {
    private final PolicyConsentService policies;
    private final CustomerAccessPrincipal principal;

    public IdentityPolicyController(
            PolicyConsentService policies,
            CustomerAccessPrincipal principal) {
        this.policies = policies;
        this.principal = principal;
    }

    @GetMapping("/current")
    public List<CurrentPolicyDecision> current(
            @RequestParam String language,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return policies.currentPolicies(principal.accountId(), language);
    }

    @GetMapping("/reactivation")
    public List<PolicyDocumentVersion> reactivation(@RequestParam String language) {
        return policies.requiredReactivationPolicies(language);
    }

    @GetMapping("/{policyDocumentId}/consents")
    public List<AccountConsent> history(
            @PathVariable UUID policyDocumentId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return policies.history(principal.accountId(), policyDocumentId);
    }

    @PostMapping("/{policyDocumentId}/consents")
    public AccountConsent decide(
            @PathVariable UUID policyDocumentId,
            @RequestBody ConsentDecisionRequest request,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        UUID correlation = correlation(correlationId);
        return policies.decide(
                principal.accountId(),
                new RecordConsentDecision(policyDocumentId, request.decision(), correlation));
    }

    private static UUID correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
    }

    public record ConsentDecisionRequest(String decision) {}
}
