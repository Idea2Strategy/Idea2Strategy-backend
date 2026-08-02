package com.idea2strategy.backend.application.accountsanction;

@FunctionalInterface
public interface AccountSanctionDecision {
    AccountSanctionResult decide(
            AccountSanctionState state,
            AccountSanctionAuthorizationPort.Decision authorization);
}
