package com.idea2strategy.backend.batch;

import java.util.UUID;

@FunctionalInterface
public interface NotificationRecipientResolver {
    String requireEmail(UUID accountId);
}
