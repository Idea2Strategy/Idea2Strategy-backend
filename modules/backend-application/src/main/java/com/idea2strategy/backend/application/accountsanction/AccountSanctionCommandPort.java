package com.idea2strategy.backend.application.accountsanction;

import java.time.Instant;

public interface AccountSanctionCommandPort {
    /**
     * Resolves idempotency, locks the account sanction aggregate, checks its version, persists the
     * decision and receipt, and invokes effects before the same transaction commits.
     */
    AccountSanctionResult executeAtomically(
            AccountSanctionCommand command,
            Instant evaluatedAt,
            AccountSanctionAuthorizationPort.Decision authorization,
            AccountSanctionDecision decision,
            TransactionalEffects effects);

    @FunctionalInterface
    interface TransactionalEffects {
        void publish(AccountSanctionResult result);
    }
}
