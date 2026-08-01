package com.idea2strategy.backend.common.contract.v1;

import java.util.Objects;

public final class FakePrincipalProvider implements PrincipalProvider {
    private final AuthenticationPrincipal principal;

    public FakePrincipalProvider(AuthenticationPrincipal principal) {
        this.principal = Objects.requireNonNull(principal, "principal is required");
    }

    @Override
    public AuthenticationPrincipal currentPrincipal() {
        return principal;
    }
}
