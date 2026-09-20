package com.idea2strategy.backend.application.identity;

import java.util.Optional;

public interface IdentityQueryPort {
    Optional<PasswordLoginAccount> findPasswordLoginByEmailLookup(String emailLookup);

    /**
     * The same account, found by id rather than by email.
     *
     * <p>A device authorization already knows who approved it — the browser session said so — and
     * still needs the login identity, auth epoch, and credential version to mint a session that
     * dies with a password change like any other.
     */
    Optional<PasswordLoginAccount> findPasswordLoginByAccountId(java.util.UUID accountId);
}
