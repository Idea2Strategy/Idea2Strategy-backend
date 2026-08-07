package com.idea2strategy.backend.application.identity;

public interface IdentityCommandPort {
    void createSession(AuthenticationSession session);

    void recordLoginFailure(LoginFailure failure);

    default void recordLoginSuccess(AuthenticationSuccess success) {}

    default void recordStepUpSuccess(AuthenticationSuccess success) {}

    default void completeLogin(AuthenticationSession session, AuthenticationSuccess success) {
        createSession(session);
        recordLoginSuccess(success);
    }

}
