package com.idea2strategy.backend.application.identity;

import java.util.Optional;

@FunctionalInterface
public interface AccountLifecycleDecision {
    Optional<AccountLifecycleMutation> decide(AccountLifecycleSnapshot current);
}
