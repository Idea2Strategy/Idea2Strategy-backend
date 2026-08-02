package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class UserPostEvaluationChoiceService {
    private final PostEvaluationChoicePort port;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public UserPostEvaluationChoiceService(PostEvaluationChoicePort port, CurrentPrincipal principal, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PostEvaluationChoice find(UUID roomId, UUID participationId) {
        return port.findOwned(
                Objects.requireNonNull(roomId, "roomId"),
                Objects.requireNonNull(participationId, "participationId"),
                principal.accountId());
    }

    public PostEvaluationChoice update(
            UUID roomId, UUID participationId, PostEvaluationAction action) {
        return port.updateOwned(
                Objects.requireNonNull(roomId, "roomId"),
                Objects.requireNonNull(participationId, "participationId"),
                principal.accountId(),
                requiredAction(action),
                clock.instant());
    }

    private static PostEvaluationAction requiredAction(PostEvaluationAction action) {
        if (action == null) {
            throw new IllegalArgumentException("action must be explicitly selected");
        }
        return action;
    }
}
