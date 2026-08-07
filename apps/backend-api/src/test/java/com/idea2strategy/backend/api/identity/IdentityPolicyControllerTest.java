package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.PolicyConsentService;
import com.idea2strategy.backend.domain.identity.PolicyDocumentVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityPolicyControllerTest {
    @Test
    void signedOutReactivationCanReadOnlyTheRequiredCurrentPolicies() {
        var policies = mock(PolicyConsentService.class);
        var principal = mock(CustomerAccessPrincipal.class);
        var document = new PolicyDocumentVersion(
                UUID.randomUUID(), "TERMS", "2", "ko", "이용약관",
                "text/markdown", "필수 약관", "sha256:terms", true,
                Instant.parse("2026-08-07T00:00:00Z"), null);
        when(policies.requiredReactivationPolicies("ko")).thenReturn(List.of(document));
        var controller = new IdentityPolicyController(policies, principal);

        assertThat(controller.reactivation("ko")).containsExactly(document);
        verify(policies).requiredReactivationPolicies("ko");
    }
}
