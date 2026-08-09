package com.idea2strategy.backend.persistence.delegation;

/**
 * A delegation named a strategy it cannot target.
 *
 * <p>The target insert selects from the strategy row itself, so this is raised when the strategy is
 * not the granting account's own live Basic strategy. Failing here rather than skipping the row
 * matters: a delegation whose targets silently vanished would be granted, returned to the caller,
 * and then authorize nothing.
 */
public class DelegatedStrategyTargetRejectedException extends RuntimeException {
    public DelegatedStrategyTargetRejectedException(String message) {
        super(message);
    }
}
