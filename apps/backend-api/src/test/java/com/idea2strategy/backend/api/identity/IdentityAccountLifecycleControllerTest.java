package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.AccountLifecycleAuthenticationMethod;
import com.idea2strategy.backend.application.identity.AccountLifecycleAuthenticationProof;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommand;
import com.idea2strategy.backend.application.identity.AccountLifecycleResult;
import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import com.idea2strategy.backend.application.identity.LifecyclePasswordStepUpService;
import com.idea2strategy.backend.application.identity.LifecycleStepUp;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IdentityAccountLifecycleControllerTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-08-02T08:00:00Z");

    @Test
    void requestsWithdrawalUsingOnlyTheStepUpAccountAndASecretFreeStableHash() {
        var lifecycle = mock(AccountLifecycleService.class);
        var stepUp = mock(LifecyclePasswordStepUpService.class);
        var proof = new AccountLifecycleAuthenticationProof(
                AccountLifecycleAuthenticationMethod.PASSWORD, AUTHENTICATED_AT, true);
        UUID correlationId = UUID.randomUUID();
        when(stepUp.authenticate("person@example.com", "top-secret", correlationId))
                .thenReturn(new LifecycleStepUp(ACCOUNT_ID, proof));
        when(lifecycle.requestWithdrawal(any())).thenReturn(result(AccountLifecycleStatus.CLOSING));
        var controller = new IdentityAccountLifecycleController(lifecycle, stepUp);
        var response = controller.requestWithdrawal(
                new IdentityAccountLifecycleController.PasswordStepUpRequest("person@example.com", "top-secret"),
                "withdraw-1", correlationId.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().status()).isEqualTo(AccountLifecycleStatus.CLOSING);
        var command = captureWithdrawal(lifecycle);
        assertThat(command.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(command.idempotencyKey()).isEqualTo("withdraw-1");
        assertThat(command.correlationId()).isEqualTo(correlationId);
        assertThat(command.proof()).isEqualTo(proof);
        assertThat(command.requestHash()).doesNotContain("person@example.com").doesNotContain("top-secret");
        assertThat(command.requestHash()).hasSize(64);
        assertThat(response.getBody().toString()).doesNotContain("top-secret");
    }

    @Test
    void cancellationCanReauthenticateAClosingAccountAndGeneratesCorrelationWhenAbsent() {
        var lifecycle = mock(AccountLifecycleService.class);
        var stepUp = mock(LifecyclePasswordStepUpService.class);
        var proof = new AccountLifecycleAuthenticationProof(
                AccountLifecycleAuthenticationMethod.PASSWORD, AUTHENTICATED_AT, true);
        when(stepUp.authenticate(
                        org.mockito.ArgumentMatchers.eq("person@example.com"),
                        org.mockito.ArgumentMatchers.eq("top-secret"),
                        any(UUID.class)))
                .thenReturn(new LifecycleStepUp(ACCOUNT_ID, proof));
        when(lifecycle.cancelWithdrawal(any())).thenReturn(result(AccountLifecycleStatus.ACTIVE));
        var controller = new IdentityAccountLifecycleController(lifecycle, stepUp);

        var response = controller.cancelWithdrawal(
                new IdentityAccountLifecycleController.PasswordStepUpRequest("person@example.com", "top-secret"),
                "cancel-1", null);

        assertThat(response.status()).isEqualTo(AccountLifecycleStatus.ACTIVE);
        var captor = ArgumentCaptor.forClass(AccountLifecycleCommand.class);
        verify(lifecycle).cancelWithdrawal(captor.capture());
        var correlationCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(stepUp).authenticate(
                org.mockito.ArgumentMatchers.eq("person@example.com"),
                org.mockito.ArgumentMatchers.eq("top-secret"),
                correlationCaptor.capture());
        assertThat(captor.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(captor.getValue().correlationId()).isNotNull();
        assertThat(captor.getValue().correlationId()).isEqualTo(correlationCaptor.getValue());
        assertThat(captor.getValue().requestHash()).isNotEqualTo(
                IdentityAccountLifecycleController.requestHash(ACCOUNT_ID, "REQUEST_WITHDRAWAL"));
    }

    @Test
    void requestBodyAlwaysRedactsBothCredentials() {
        var request = new IdentityAccountLifecycleController.PasswordStepUpRequest(
                "person@example.com", "top-secret");

        assertThat(request.toString()).isEqualTo("PasswordStepUpRequest[credentials=REDACTED]");
    }

    @Test
    void mapsStepUpRejectionToLifecycleSafeErrorWithTheRequestCorrelation() {
        var lifecycle = mock(AccountLifecycleService.class);
        var stepUp = mock(LifecyclePasswordStepUpService.class);
        UUID correlationId = UUID.randomUUID();
        when(stepUp.authenticate("person@example.com", "wrong", correlationId))
                .thenThrow(new AuthenticationRejectedException("Invalid email or password"));
        var controller = new IdentityAccountLifecycleController(lifecycle, stepUp);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.requestWithdrawal(
                        new IdentityAccountLifecycleController.PasswordStepUpRequest("person@example.com", "wrong"),
                        "withdraw-rejected", correlationId.toString()))
                .isInstanceOf(LifecycleRequestRejectedException.class)
                .satisfies(error -> assertThat(((LifecycleRequestRejectedException) error).code())
                        .isEqualTo("STEP_UP_REQUIRED"))
                .satisfies(error -> assertThat(((LifecycleRequestRejectedException) error).correlationId())
                        .isEqualTo(correlationId));
    }

    private static AccountLifecycleCommand captureWithdrawal(AccountLifecycleService service) {
        var captor = ArgumentCaptor.forClass(AccountLifecycleCommand.class);
        verify(service).requestWithdrawal(captor.capture());
        return captor.getValue();
    }

    private static AccountLifecycleResult result(AccountLifecycleStatus status) {
        return new AccountLifecycleResult(
                ACCOUNT_ID, status, 2, AUTHENTICATED_AT, AUTHENTICATED_AT.plusSeconds(60), true);
    }
}
