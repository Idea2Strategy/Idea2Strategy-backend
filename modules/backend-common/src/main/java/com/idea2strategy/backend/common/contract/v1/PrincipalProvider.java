package com.idea2strategy.backend.common.contract.v1;

@FunctionalInterface
public interface PrincipalProvider {
    AuthenticationPrincipal currentPrincipal();
}
