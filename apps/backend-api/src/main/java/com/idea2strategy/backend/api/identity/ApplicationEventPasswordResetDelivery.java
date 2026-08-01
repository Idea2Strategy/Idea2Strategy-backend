package com.idea2strategy.backend.api.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

public final class ApplicationEventPasswordResetDelivery implements PasswordResetDeliveryPort {
    private final ApplicationEventPublisher publisher;

    public ApplicationEventPasswordResetDelivery(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void send(UUID accountId, String resetToken, Instant expiresAt) {
        publisher.publishEvent(new PasswordResetEmailRequested(accountId, resetToken, expiresAt));
    }
}
