package com.idea2strategy.backend.api.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

public final class ApplicationEventVerificationDelivery implements VerificationDeliveryPort {
    private final ApplicationEventPublisher publisher;

    public ApplicationEventVerificationDelivery(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void send(String email, String verificationToken, Instant expiresAt) {
        publisher.publishEvent(new VerificationEmailRequested(email, verificationToken, expiresAt));
    }

    @Override
    public void send(UUID accountId, String verificationToken, Instant expiresAt) {
        publisher.publishEvent(new AccountVerificationEmailRequested(accountId, verificationToken, expiresAt));
    }
}
