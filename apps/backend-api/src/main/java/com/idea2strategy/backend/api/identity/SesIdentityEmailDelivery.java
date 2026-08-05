package com.idea2strategy.backend.api.identity;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import org.springframework.context.event.EventListener;
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

public final class SesIdentityEmailDelivery {
    private final SesV2Client ses;
    private final String fromAddress;
    private final URI baseUrl;
    private final IdentityEmailAddressResolver addresses;

    public SesIdentityEmailDelivery(
            SesV2Client ses, String fromAddress, URI baseUrl, IdentityEmailAddressResolver addresses) {
        this.ses = Objects.requireNonNull(ses, "ses");
        this.fromAddress = required(fromAddress, "fromAddress");
        this.baseUrl = requireHttpsBaseUrl(baseUrl);
        this.addresses = Objects.requireNonNull(addresses, "addresses");
    }

    @EventListener
    public void onVerificationRequested(VerificationEmailRequested event) {
        send(event.email(), "Idea2Strategy 이메일 인증",
                body("이메일 인증", verificationUrl(event.verificationToken()), event.expiresAt()));
    }

    @EventListener
    public void onAccountVerificationRequested(AccountVerificationEmailRequested event) {
        send(addresses.requireEmail(event.accountId()), "Idea2Strategy 이메일 인증",
                body("이메일 인증", verificationUrl(event.verificationToken()), event.expiresAt()));
    }

    @EventListener
    public void onPasswordResetRequested(PasswordResetEmailRequested event) {
        send(addresses.requireEmail(event.accountId()), "Idea2Strategy 비밀번호 재설정",
                body("비밀번호 재설정", resetUrl(event.resetToken()), event.expiresAt()));
    }

    private void send(String recipient, String subject, String body) {
        ensureNotSuppressed(recipient);
        var request = SendEmailRequest.builder()
                .fromEmailAddress(fromAddress)
                .destination(Destination.builder().toAddresses(recipient).build())
                .content(EmailContent.builder().simple(software.amazon.awssdk.services.sesv2.model.Message.builder()
                        .subject(Content.builder().data(subject).charset("UTF-8").build())
                        .body(Body.builder().text(Content.builder().data(body).charset("UTF-8").build()).build())
                        .build()).build())
                .build();
        try {
            ses.sendEmail(request);
        } catch (TooManyRequestsException exception) {
            throw new EmailDeliveryUnavailableException(true);
        } catch (SesV2Exception exception) {
            int status = exception.statusCode();
            throw new EmailDeliveryUnavailableException(status == 429 || status >= 500);
        } catch (SdkClientException exception) {
            throw new EmailDeliveryUnavailableException(true);
        }
    }

    private void ensureNotSuppressed(String recipient) {
        try {
            var response = ses.getSuppressedDestination(GetSuppressedDestinationRequest.builder()
                    .emailAddress(recipient).build());
            if (response.suppressedDestination() != null) {
                throw new EmailDeliveryUnavailableException(false);
            }
        } catch (NotFoundException ignored) {
            // Not present in the SES account-level bounce/complaint suppression list.
        } catch (EmailDeliveryUnavailableException exception) {
            throw exception;
        } catch (SesV2Exception exception) {
            int status = exception.statusCode();
            throw new EmailDeliveryUnavailableException(status == 429 || status >= 500);
        } catch (SdkClientException exception) {
            throw new EmailDeliveryUnavailableException(true);
        }
    }

    private String verificationUrl(String token) {
        return baseUrl + "/account?verificationToken=" + encode(token);
    }

    private String resetUrl(String token) {
        return baseUrl + "/account?resetToken=" + encode(token);
    }

    private static String body(String action, String url, Instant expiresAt) {
        return "Idea2Strategy " + action + " 요청입니다.\n\n" + url
                + "\n\n만료 시각(UTC): " + expiresAt + "\n본인이 요청하지 않았다면 이 메일을 무시하세요.";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static URI requireHttpsBaseUrl(URI value) {
        Objects.requireNonNull(value, "baseUrl");
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
                || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an HTTPS origin");
        }
        String normalized = value.toString().replaceAll("/+$", "");
        return URI.create(normalized);
    }

    @Override
    public String toString() {
        return "SesIdentityEmailDelivery[provider=ses]";
    }
}
