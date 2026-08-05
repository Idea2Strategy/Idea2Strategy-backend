package com.idea2strategy.backend.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.persistence.notification.EmailDeliveryGateway;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.GetSuppressedDestinationResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SuppressionListReason;
import software.amazon.awssdk.services.sesv2.model.SuppressedDestination;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

class SesNotificationEmailDeliveryGatewayTest {
    private static final UUID NOTIFICATION = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final EmailDeliveryGateway.EmailMessage MESSAGE = new EmailDeliveryGateway.EmailMessage(
            NOTIFICATION, ACCOUNT, "security-v1", "ko", Map.of("event", "PASSWORD_CHANGED"));

    @Test
    void sendsRenderedSnapshotAndReturnsProviderMessageKey() {
        SesV2Client ses = availableSes();
        var gateway = new SesNotificationEmailDeliveryGateway(
                ses, "no-reply@ideatostrategy.com", ignored -> "person@example.com");

        var result = gateway.send(MESSAGE);

        assertThat(result.outcome()).isEqualTo(EmailDeliveryGateway.DeliveryResult.Outcome.SENT);
        assertThat(result.providerMessageKey()).isEqualTo("provider-message-id");
        var captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(captor.capture());
        assertThat(captor.getValue().destination().toAddresses()).containsExactly("person@example.com");
        assertThat(captor.getValue().content().simple().body().text().data())
                .contains("security-v1", "event: PASSWORD_CHANGED");
    }

    @Test
    void mapsSuppressionToPermanentFailureAndThrottlingToRetry() {
        SesV2Client suppressed = mock(SesV2Client.class);
        when(suppressed.getSuppressedDestination(any(GetSuppressedDestinationRequest.class)))
                .thenReturn(GetSuppressedDestinationResponse.builder()
                        .suppressedDestination(SuppressedDestination.builder()
                                .emailAddress("person@example.com")
                                .reason(SuppressionListReason.COMPLAINT).build()).build());
        var suppressedGateway = new SesNotificationEmailDeliveryGateway(
                suppressed, "no-reply@ideatostrategy.com", ignored -> "person@example.com");
        assertThat(suppressedGateway.send(MESSAGE)).isEqualTo(
                EmailDeliveryGateway.DeliveryResult.permanent("SES_DESTINATION_SUPPRESSED"));

        SesV2Client throttled = availableSes();
        when(throttled.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(TooManyRequestsException.builder().message("sensitive provider detail").build());
        var throttledGateway = new SesNotificationEmailDeliveryGateway(
                throttled, "no-reply@ideatostrategy.com", ignored -> "person@example.com");
        assertThat(throttledGateway.send(MESSAGE)).isEqualTo(
                EmailDeliveryGateway.DeliveryResult.retry("SES_RETRYABLE"));
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
