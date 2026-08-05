package com.idea2strategy.backend.persistence.notification;

import java.util.Map;
import java.util.UUID;

public interface EmailDeliveryGateway {
    DeliveryResult send(EmailMessage message);

    record EmailMessage(UUID notificationId, UUID accountId, String templateVersion, String locale,
                        Map<String, String> templateArguments) {}

    record DeliveryResult(Outcome outcome, String providerMessageKey, String failureCode) {
        public enum Outcome { SENT, RETRYABLE_FAILURE, PERMANENT_FAILURE }
        public static DeliveryResult sent(String key) { return new DeliveryResult(Outcome.SENT, key, null); }
        public static DeliveryResult retry(String code) { return new DeliveryResult(Outcome.RETRYABLE_FAILURE, null, code); }
        public static DeliveryResult permanent(String code) { return new DeliveryResult(Outcome.PERMANENT_FAILURE, null, code); }
    }
}
