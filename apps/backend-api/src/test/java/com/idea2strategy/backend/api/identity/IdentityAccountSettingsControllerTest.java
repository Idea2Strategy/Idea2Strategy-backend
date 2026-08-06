package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.AccountPreferencesService;
import com.idea2strategy.backend.application.identity.PolicyConsentService;
import com.idea2strategy.backend.application.identity.UpdateAccountPreferences;
import com.idea2strategy.backend.domain.identity.AccountConsent;
import com.idea2strategy.backend.domain.identity.ConsentDecision;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityAccountSettingsControllerTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Test
    void preferenceUpdateUsesOnlyTheAuthenticatedAccountId() {
        var preferences = mock(AccountPreferencesService.class);
        var principal = mock(CustomerAccessPrincipal.class);
        UUID correlationId = UUID.randomUUID();
        when(principal.accountId()).thenReturn(ACCOUNT_ID);
        var controller = new IdentityPreferencesController(preferences, principal);

        controller.update(
                new IdentityPreferencesController.UpdatePreferencesRequest("en", "America/Chicago", "DARK"),
                "Bearer opaque-token",
                correlationId.toString());

        verify(preferences).update(
                eq(ACCOUNT_ID),
                eq(new UpdateAccountPreferences("en", "America/Chicago", "DARK", correlationId)));
    }

    @Test
    void consentDecisionUsesTheExactDocumentAndAuthenticatedAccount() {
        var policies = mock(PolicyConsentService.class);
        var principal = mock(CustomerAccessPrincipal.class);
        UUID documentId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        var consent = new AccountConsent(
                UUID.randomUUID(), ACCOUNT_ID, documentId, ConsentDecision.ACCEPTED, null, Instant.now());
        when(principal.accountId()).thenReturn(ACCOUNT_ID);
        when(policies.decide(eq(ACCOUNT_ID), any())).thenReturn(consent);
        var controller = new IdentityPolicyController(policies, principal);

        var response = controller.decide(
                documentId,
                new IdentityPolicyController.ConsentDecisionRequest("ACCEPTED"),
                "Bearer opaque-token",
                correlationId.toString());

        assertThat(response).isEqualTo(consent);
        verify(policies).decide(eq(ACCOUNT_ID), any());
    }
}
