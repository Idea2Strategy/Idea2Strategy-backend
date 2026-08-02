package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.UUID;

public interface PostEvaluationChoicePort {
    PostEvaluationChoice findOwned(UUID roomId, UUID participationId, UUID ownerAccountId);

    PostEvaluationChoice updateOwned(
            UUID roomId,
            UUID participationId,
            UUID ownerAccountId,
            PostEvaluationAction action,
            Instant recordedAt);
}
