package com.idea2strategy.backend.application.identity;

public interface IdentityCommandPort {
    void createRefreshTokenFamily(RefreshTokenFamily family);

    void recordLoginFailure(LoginFailure failure);

    default void recordLoginSuccess(AuthenticationSuccess success) {}

    default void recordStepUpSuccess(AuthenticationSuccess success) {}

    default void completeLogin(RefreshTokenFamily family, AuthenticationSuccess success) {
        createRefreshTokenFamily(family);
        recordLoginSuccess(success);
    }

}
