package com.idea2strategy.backend.application.delegation;

import java.util.Optional;

@FunctionalInterface
public interface DelegatedAuthorizationDecision {
    DelegatedAuthorizationMutation decide(Optional<DelegatedAuthorizationSnapshot> current);
}
