package com.idea2strategy.backend.application.usercase;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class UserCaseService {
    private final UserCaseStore store;
    private final Clock clock;

    public UserCaseService(UserCaseStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public UserCaseView submit(UserCaseCommand command) {
        return requireSuccess(store.submit(command, clock.instant()));
    }

    public UserCaseView supplement(UserCaseSupplementCommand command) {
        return requireSuccess(store.supplement(command, clock.instant()));
    }

    public UserCaseView detail(UUID accountId, UUID caseId) {
        return store.findOwned(accountId, caseId)
                .orElseThrow(() -> new UserCaseRejectedException("RESOURCE_NOT_AVAILABLE"));
    }

    private UserCaseView requireSuccess(UserCaseStore.CommandResult result) {
        return switch (result.outcome()) {
            case APPLIED, REPLAYED -> Objects.requireNonNull(result.view(), "successful command view");
            case IDEMPOTENCY_CONFLICT -> throw new UserCaseRejectedException("IDEMPOTENCY_KEY_REUSED");
            case RESOURCE_NOT_AVAILABLE -> throw new UserCaseRejectedException("RESOURCE_NOT_AVAILABLE");
            case TRANSITION_NOT_ALLOWED -> throw new UserCaseRejectedException("CASE_TRANSITION_NOT_ALLOWED");
            case STALE_VERSION -> throw new UserCaseRejectedException("STALE_CASE_VERSION");
        };
    }
}
