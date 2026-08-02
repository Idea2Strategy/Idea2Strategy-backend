package com.idea2strategy.backend.application.caseoperations;

import java.util.Map;
import java.util.UUID;

public interface CaseNotificationOutboxPort {
    void stageInCurrentTransaction(Intent intent);

    record Intent(
            UUID caseId,
            UUID accountId,
            String eventType,
            long caseVersion,
            UUID correlationId,
            String idempotencyKey,
            Map<String, Object> publicPayload) {
        public Intent {
            publicPayload = Map.copyOf(publicPayload);
        }
    }
}
