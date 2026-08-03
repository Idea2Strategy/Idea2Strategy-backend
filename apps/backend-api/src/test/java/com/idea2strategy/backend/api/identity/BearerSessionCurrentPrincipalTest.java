package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.AuthenticatedSession;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BearerSessionCurrentPrincipalTest {
    private static final UUID ACCOUNT = UUID.fromString("a2200000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("a2200000-0000-4000-8000-000000000002");
    private static final UUID CORRELATION = UUID.fromString("a2200000-0000-4000-8000-000000000003");

    @Test
    void authenticatesTheBackendSessionOncePerRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        SessionManagementService sessions = mock(SessionManagementService.class);
        HmacSessionTokens tokens = new HmacSessionTokens(new byte[32]);
        when(request.getHeader("Authorization")).thenReturn("Bearer session-secret");
        when(request.getHeader("X-Correlation-Id")).thenReturn(CORRELATION.toString());
        when(sessions.authenticate(tokens.digest("session-secret"), CORRELATION))
                .thenReturn(new AuthenticatedSession(ACCOUNT, SESSION));

        var principal = new BearerSessionCurrentPrincipal(request, sessions, tokens);

        assertThat(principal.accountId()).isEqualTo(ACCOUNT);
        assertThat(principal.sessionId()).isEqualTo(SESSION);
        verify(sessions).authenticate(tokens.digest("session-secret"), CORRELATION);
    }

    @Test
    void rejectsMissingOrMalformedBearerCredentialsWithoutCallingTheSessionStore() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        SessionManagementService sessions = mock(SessionManagementService.class);
        when(request.getHeader("Authorization")).thenReturn("Basic credential");

        var principal = new BearerSessionCurrentPrincipal(
                request, sessions, new HmacSessionTokens(new byte[32]));

        assertThatThrownBy(principal::accountId)
                .isInstanceOf(AuthenticationRejectedException.class);
    }
}
