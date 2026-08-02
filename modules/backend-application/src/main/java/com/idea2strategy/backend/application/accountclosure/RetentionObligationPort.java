package com.idea2strategy.backend.application.accountclosure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RetentionObligationPort {
    List<RetentionObligation> findDueObligations(int limit, Instant now);
    boolean hasActiveLegalHold(UUID accountId, String dataCategory);
    void markHeld(UUID obligationId, Instant at);
    void markCompleted(UUID obligationId, Instant at);
    void markFailed(UUID obligationId, String failureCode, Instant at);
    int resumeReleasedHolds(Instant at);
}
