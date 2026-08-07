package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public final class CustomerAccessValidationService {
    private final CustomerAccessStateQueryPort states;

    public CustomerAccessValidationService(CustomerAccessStateQueryPort states) {
        this.states = Objects.requireNonNull(states, "states");
    }

    public AuthenticatedCustomer authenticate(
            UUID accountId,
            UUID loginIdentityId,
            long authEpoch,
            Long credentialVersion,
            CustomerAccessScope accessScope) {
        CustomerAccessState state = states.findCustomerAccessState(accountId, loginIdentityId)
                .orElseThrow(() -> new AuthenticationRejectedException("Customer JWT is no longer valid"));
        if (state.accountStatus() != AccountLifecycleStatus.ACTIVE
                || state.loginIdentityStatus() != LoginIdentityStatus.ACTIVE
                || state.authEpoch() != authEpoch
                || !Objects.equals(state.credentialVersion(), credentialVersion)) {
            throw new AuthenticationRejectedException("Customer JWT is no longer valid");
        }
        Objects.requireNonNull(accessScope, "accessScope");
        if (state.activeSanction() && !accessScope.allowedDuringSanction()) {
            throw new SanctionedAccountAccessException();
        }
        return new AuthenticatedCustomer(state.accountId(), state.activeSanction());
    }
}
