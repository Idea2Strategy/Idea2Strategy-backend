package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.AuthenticatedSession;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.CustomerAccessScope;
import com.idea2strategy.backend.application.identity.SessionManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BearerSessionCurrentPrincipalTest {
    private static final UUID ACCOUNT = UUID.fromString("a2200000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("a2200000-0000-4000-8000-000000000002");

    @Test
    void resolvesTheJwtAndValidatesItsServerSideSession() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CustomerJwtCodec jwt = jwt();
        SessionManagementService sessions = mock(SessionManagementService.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + jwt.issueAccess(ACCOUNT, SESSION));
        when(request.getHeader("X-Correlation-Id"))
                .thenReturn("a2200000-0000-4000-8000-000000000003");
        UUID correlation = UUID.fromString("a2200000-0000-4000-8000-000000000003");
        when(sessions.authenticateAccess(ACCOUNT, SESSION, correlation, CustomerAccessScope.STANDARD))
                .thenReturn(new AuthenticatedSession(ACCOUNT, SESSION, false));
        when(sessions.authenticateAccess(ACCOUNT, SESSION, correlation, CustomerAccessScope.APPEAL))
                .thenReturn(new AuthenticatedSession(ACCOUNT, SESSION, true));

        var principal = new BearerSessionCurrentPrincipal(request, jwt, sessions);

        assertThat(principal.accountId()).isEqualTo(ACCOUNT);
        assertThat(principal.accountId(CustomerAccessScope.APPEAL)).isEqualTo(ACCOUNT);
        assertThat(principal.sessionId()).isEqualTo(SESSION);
        assertThat(principal.activeSanction()).isTrue();
        verify(sessions).authenticateAccess(ACCOUNT, SESSION, correlation, CustomerAccessScope.STANDARD);
        verify(sessions).authenticateAccess(ACCOUNT, SESSION, correlation, CustomerAccessScope.APPEAL);
    }

    @Test
    void rejectsMissingMalformedOrRefreshCredentials() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CustomerJwtCodec jwt = jwt();
        SessionManagementService sessions = mock(SessionManagementService.class);
        when(request.getHeader("Authorization")).thenReturn("Basic credential");
        assertThatThrownBy(() -> new BearerSessionCurrentPrincipal(request, jwt, sessions).accountId())
                .isInstanceOf(AuthenticationRejectedException.class);

        when(request.getHeader("Authorization")).thenReturn("Bearer "
                + jwt.issueRefresh(ACCOUNT, SESSION, "refresh-secret", Instant.parse("2026-08-06T12:00:00Z")));
        assertThatThrownBy(() -> new BearerSessionCurrentPrincipal(request, jwt, sessions).accountId())
                .isInstanceOf(AuthenticationRejectedException.class);
    }

    private static CustomerJwtCodec jwt() {
        return new CustomerJwtCodec(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC),
                "https://ideatostrategy.com", "idea2strategy-api", "idea2strategy-refresh",
                Duration.ofMinutes(5));
    }
}
