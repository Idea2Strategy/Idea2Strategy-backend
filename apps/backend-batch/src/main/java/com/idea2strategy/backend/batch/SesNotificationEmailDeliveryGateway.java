package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.persistence.notification.EmailDeliveryGateway;
import java.util.Comparator;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.GetSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

public final class SesNotificationEmailDeliveryGateway implements EmailDeliveryGateway {
    private final SesV2Client ses;
    private final String fromAddress;
    private final NotificationRecipientResolver recipients;

    public SesNotificationEmailDeliveryGateway(
            SesV2Client ses, String fromAddress, NotificationRecipientResolver recipients) {
        this.ses = Objects.requireNonNull(ses, "ses");
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalArgumentException("fromAddress is required");
        }
        this.fromAddress = fromAddress;
        this.recipients = Objects.requireNonNull(recipients, "recipients");
    }

    @Override
    public DeliveryResult send(EmailMessage message) {
        final String recipient;
        try {
            recipient = recipients.requireEmail(message.accountId());
        } catch (RuntimeException exception) {
            return DeliveryResult.permanent("RECIPIENT_UNAVAILABLE");
        }
        DeliveryResult suppression = suppressionStatus(recipient);
        if (suppression != null) return suppression;

        try {
            var response = ses.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(fromAddress)
                    .destination(Destination.builder().toAddresses(recipient).build())
                    .content(EmailContent.builder().simple(software.amazon.awssdk.services.sesv2.model.Message.builder()
                            .subject(Content.builder().data("Idea2Strategy 알림").charset("UTF-8").build())
                            .body(Body.builder().text(Content.builder()
                                    .data(render(message)).charset("UTF-8").build()).build())
                            .build()).build())
                    .build());
            return DeliveryResult.sent(response.messageId());
        } catch (TooManyRequestsException exception) {
            return DeliveryResult.retry("SES_RETRYABLE");
        } catch (SesV2Exception exception) {
            return transientFailure(exception.statusCode())
                    ? DeliveryResult.retry("SES_RETRYABLE")
                    : DeliveryResult.permanent("SES_REJECTED");
        } catch (SdkClientException exception) {
            return DeliveryResult.retry("SES_RETRYABLE");
        }
    }

    private DeliveryResult suppressionStatus(String recipient) {
        try {
            var response = ses.getSuppressedDestination(GetSuppressedDestinationRequest.builder()
                    .emailAddress(recipient).build());
            return response.suppressedDestination() == null
                    ? null : DeliveryResult.permanent("SES_DESTINATION_SUPPRESSED");
        } catch (NotFoundException ignored) {
            return null;
        } catch (SesV2Exception exception) {
            return transientFailure(exception.statusCode())
                    ? DeliveryResult.retry("SES_RETRYABLE")
                    : DeliveryResult.permanent("SES_SUPPRESSION_CHECK_REJECTED");
        } catch (SdkClientException exception) {
            return DeliveryResult.retry("SES_RETRYABLE");
        }
    }

    private static boolean transientFailure(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static String render(EmailMessage message) {
        StringBuilder body = new StringBuilder("Idea2Strategy 알림입니다.\n\n템플릿: ")
                .append(message.templateVersion()).append("\n언어: ").append(message.locale());
        message.templateArguments().entrySet().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .forEach(entry -> body.append('\n').append(entry.getKey()).append(": ").append(entry.getValue()));
        return body.toString();
    }

    @Override
    public String toString() {
        return "SesNotificationEmailDeliveryGateway[provider=ses]";
    }
}
