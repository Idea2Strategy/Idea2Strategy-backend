package com.idea2strategy.backend.application.accountsanction;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface AccountSanctionOutboxPublicationPort {
    void publish(List<Message> messages);

    record Message(
            String type,
            UUID accountId,
            UUID sanctionId,
            UUID correlationId,
            String deduplicationKey,
            Instant occurredAt) {

        public Message {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(sanctionId, "sanctionId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(deduplicationKey, "deduplicationKey");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
