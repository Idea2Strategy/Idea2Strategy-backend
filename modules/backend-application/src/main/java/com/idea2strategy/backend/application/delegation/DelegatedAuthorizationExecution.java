package com.idea2strategy.backend.application.delegation;

import java.util.Objects;

public record DelegatedAuthorizationExecution(DelegatedAuthorizationResult result, boolean newlyApplied) {
    public DelegatedAuthorizationExecution {
        Objects.requireNonNull(result, "result");
    }
}
