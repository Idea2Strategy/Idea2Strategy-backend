package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.identity.SanctionedAccountAccessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityLifecycleExceptionHandlerTest {
    @Test
    void returnsTheStableSanctionCodeWithoutOperationalEvidence() {
        var response = new IdentityAuthExceptionHandler()
                .activeSanction(new SanctionedAccountAccessException());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody())
                .containsEntry("code", "ACCOUNT_SANCTION_ACTIVE")
                .containsEntry("appeal_available", true)
                .doesNotContainKeys("reason", "evidence", "operator_notes");
    }

    @Test
    void returnsForbiddenStableCodeAndCorrelationForStepUpFailure() {
        UUID correlationId = UUID.randomUUID();

        var response = new IdentityAuthExceptionHandler()
                .lifecycleRequest(new LifecycleRequestRejectedException("STEP_UP_REQUIRED", correlationId));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).containsEntry("code", "STEP_UP_REQUIRED");
        assertThat(response.getBody()).containsEntry("correlation_id", correlationId.toString());
    }

    @Test
    void returnsConflictForLifecycleDeadlineFailure() {
        UUID correlationId = UUID.randomUUID();

        var response = new IdentityAuthExceptionHandler().lifecycleRequest(
                new LifecycleRequestRejectedException("WITHDRAWAL_CANCELLATION_EXPIRED", correlationId));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("correlation_id", correlationId.toString());
    }
}
