package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.GetSuppressedDestinationResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SuppressionListReason;
import software.amazon.awssdk.services.sesv2.model.SuppressedDestination;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

class SesIdentityEmailDeliveryTest {
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void sendsVerificationAndResetLinksWithoutExposingSecretsInObjectRepresentations() {
        SesV2Client ses = availableSes();
        var listener = new SesIdentityEmailDelivery(
                ses, "no-reply@ideatostrategy.com", URI.create("https://ideatostrategy.com"),
                accountId -> "person@example.com");

        listener.onVerificationRequested(new VerificationEmailRequested(
                "person@example.com", "verification secret/+", EXPIRES_AT));
        listener.onAccountVerificationRequested(new AccountVerificationEmailRequested(
                ACCOUNT_ID, "replacement-secret", EXPIRES_AT));
        listener.onPasswordResetRequested(new PasswordResetEmailRequested(
                ACCOUNT_ID, "reset-secret", EXPIRES_AT));

        var captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses, org.mockito.Mockito.times(3)).sendEmail(captor.capture());
        var messages = captor.getAllValues();
        assertThat(messages.get(0).content().simple().body().text().data())
                .contains("https://ideatostrategy.com/api/v1/auth/verify-email?token=verification%20secret%2F%2B")
                .contains(EXPIRES_AT.toString());
        assertThat(messages.get(0).content().simple().body().html().data())
                .contains("Idea2Strategy 이메일 인증")
                .contains("https://ideatostrategy.com/api/v1/auth/verify-email?token=verification%20secret%2F%2B")
                .contains("본인이 요청하지 않았다면");
        assertThat(messages.get(1).destination().toAddresses()).containsExactly("person@example.com");
        assertThat(messages.get(2).content().simple().body().text().data())
                .contains("https://ideatostrategy.com/password-reset?token=reset-secret");
        assertThat(listener.toString()).doesNotContain("person@example.com", "secret");
    }

    @Test
    void refusesSuppressedDestinationsBeforeSending() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.getSuppressedDestination(any(GetSuppressedDestinationRequest.class)))
                .thenReturn(GetSuppressedDestinationResponse.builder()
                        .suppressedDestination(SuppressedDestination.builder()
                                .emailAddress("person@example.com")
                                .reason(SuppressionListReason.BOUNCE)
                                .build())
                        .build());
        var listener = new SesIdentityEmailDelivery(
                ses, "no-reply@ideatostrategy.com", URI.create("https://ideatostrategy.com"),
                ignored -> "person@example.com");

        assertThatThrownBy(() -> listener.onVerificationRequested(new VerificationEmailRequested(
                        "person@example.com", "do-not-log", EXPIRES_AT)))
                .isInstanceOf(EmailDeliveryUnavailableException.class)
                .hasMessage("Email delivery is unavailable")
                .hasMessageNotContaining("person@example.com")
                .hasMessageNotContaining("do-not-log")
                .extracting("retryable").isEqualTo(false);
        verify(ses, org.mockito.Mockito.never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void sanitizesAndClassifiesTransientProviderFailures() {
        SesV2Client ses = availableSes();
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(TooManyRequestsException.builder()
                .message("provider detail containing person@example.com and do-not-log")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("TooManyRequestsException").build())
                .build());
        var listener = new SesIdentityEmailDelivery(
                ses, "no-reply@ideatostrategy.com", URI.create("https://ideatostrategy.com"),
                ignored -> "person@example.com");

        assertThatThrownBy(() -> listener.onPasswordResetRequested(new PasswordResetEmailRequested(
                        ACCOUNT_ID, "do-not-log", EXPIRES_AT)))
                .isInstanceOf(EmailDeliveryUnavailableException.class)
                .hasMessage("Email delivery is unavailable")
                .hasMessageNotContaining("person@example.com")
                .hasMessageNotContaining("do-not-log")
                .extracting("retryable").isEqualTo(true);
    }

    private static SesV2Client availableSes() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.getSuppressedDestination(any(GetSuppressedDestinationRequest.class)))
                .thenThrow(NotFoundException.builder().message("not suppressed").build());
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("provider-message-id").build());
        return ses;
    }
}
