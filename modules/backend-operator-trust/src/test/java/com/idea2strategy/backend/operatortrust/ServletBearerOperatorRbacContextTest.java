package com.idea2strategy.backend.operatortrust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServletBearerOperatorRbacContextTest {
    private static final OperatorRequestContext OPERATOR = new OperatorRequestContext(
            UUID.fromString("a2200000-0000-4000-8000-000000000099"), true, true);
    private static final UUID CORRELATION = UUID.fromString("a2200000-0000-4000-8000-000000000098");

    @Test
    void passesTheTokenOnlyWhenExactlyOneStrictBearerHeaderExists() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        OperatorBearerAuthenticationService authentication = mock(OperatorBearerAuthenticationService.class);
        OperatorAuthenticationEventSink events = mock(OperatorAuthenticationEventSink.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(CORRELATION.toString());
        when(request.getHeaders("Authorization"))
                .thenReturn(Collections.enumeration(List.of("Bearer signed.jwt.value")));
        when(authentication.authenticate("signed.jwt.value", CORRELATION)).thenReturn(Optional.of(OPERATOR));

        assertThat(new ServletBearerOperatorRbacContext(request, authentication, events).current())
                .contains(OPERATOR);

        verify(request).getHeader("X-Correlation-Id");
        verify(request).getHeaders("Authorization");
        verifyNoMoreInteractions(request);
        verify(authentication).authenticate("signed.jwt.value", CORRELATION);
    }

    @Test
    void spoofedPrincipalRolesHeadersAndAlbCookieNeverProvideIdentity() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        OperatorBearerAuthenticationService authentication = mock(OperatorBearerAuthenticationService.class);
        OperatorAuthenticationEventSink events = mock(OperatorAuthenticationEventSink.class);
        when(request.getHeader("X-Correlation-Id")).thenReturn(CORRELATION.toString());
        when(request.getHeaders("Authorization")).thenReturn(Collections.emptyEnumeration());
        when(request.getUserPrincipal()).thenReturn((Principal) () -> "operator-subject");
        when(request.isUserInRole("MFA")).thenReturn(true);
        when(request.getHeader("X-Amzn-Oidc-Identity")).thenReturn("operator-subject");
        when(request.getHeader("X-Operator-Id")).thenReturn(OPERATOR.operatorId().toString());
        when(request.getHeader("X-User-Id")).thenReturn(OPERATOR.operatorId().toString());
        when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("AWSELBAuthSessionCookie-0", "forged")});

        assertThat(new ServletBearerOperatorRbacContext(request, authentication, events).current()).isEmpty();

        verify(request).getHeader("X-Correlation-Id");
        verify(request).getHeaders("Authorization");
        verifyNoMoreInteractions(request);
        verify(authentication, never()).authenticate(anyString(), any(UUID.class));
    }

    @Test
    void duplicateBlankWhitespaceAndOtherSchemesAreRejectedBeforeAuthentication() {
        for (List<String> values : List.of(
                List.of("Bearer first", "Bearer second"),
                List.of("Bearer "),
                List.of("Bearer token with-space"),
                List.of("Basic dXNlcjpwYXNz"))) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            OperatorBearerAuthenticationService authentication = mock(OperatorBearerAuthenticationService.class);
            OperatorAuthenticationEventSink events = mock(OperatorAuthenticationEventSink.class);
            when(request.getHeader("X-Correlation-Id")).thenReturn(CORRELATION.toString());
            when(request.getHeaders("Authorization")).thenReturn(Collections.enumeration(values));

            assertThat(new ServletBearerOperatorRbacContext(request, authentication, events).current())
                    .as(values.toString()).isEmpty();
            verify(authentication, never()).authenticate(anyString(), any(UUID.class));
        }
    }
}
