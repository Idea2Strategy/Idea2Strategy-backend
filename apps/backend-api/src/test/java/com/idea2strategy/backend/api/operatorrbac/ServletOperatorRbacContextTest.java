package com.idea2strategy.backend.api.operatorrbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ServletOperatorRbacContextTest {
    private static final Instant NOW = Instant.parse("2026-08-03T02:00:00Z");
    private static final UUID OPERATOR = UUID.fromString("a2200000-0000-4000-8000-000000000040");

    @Test
    void mapsOnlyAnAuthenticatedMappedSubjectWithFreshContainerMfa() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(request.getUserPrincipal()).thenReturn((Principal) () -> "operator-subject");
        when(request.isUserInRole("MFA")).thenReturn(true);
        var context = new ServletOperatorRbacContext(
                request, jdbc, new byte[32], Duration.ofMinutes(10), Clock.fixed(NOW, ZoneOffset.UTC));
        when(jdbc.queryForList(anyString(), eq(context.digest("operator-subject"))))
                .thenReturn(List.of(Map.of(
                        "id", OPERATOR,
                        "status", "ACTIVE",
                        "mfa_enrolled_at", Timestamp.from(NOW.minusSeconds(100)),
                        "last_mfa_verified_at", Timestamp.from(NOW.minusSeconds(60)))));

        assertThat(context.current()).contains(
                new com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext(
                        OPERATOR, true, true));
    }

    @Test
    void doesNotTrustHeadersWhenTheServletContainerHasNoPrincipal() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Operator-Id")).thenReturn(OPERATOR.toString());
        var context = new ServletOperatorRbacContext(
                request, mock(JdbcTemplate.class), new byte[32], Duration.ofMinutes(10),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(context.current()).isEmpty();
    }

    @Test
    void rejectsWeakSubjectProtectionKeysAtConfigurationTime() {
        assertThatThrownBy(() -> new ServletOperatorRbacContext(
                mock(HttpServletRequest.class), mock(JdbcTemplate.class), new byte[31],
                Duration.ofMinutes(10), Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OPERATOR_AUTH_CONFIGURATION_INVALID");
    }

    @Test
    void preservesTheMappedIdentityButDoesNotClaimMfaWhenProofIsStale() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(request.getUserPrincipal()).thenReturn((Principal) () -> "operator-subject");
        when(request.isUserInRole("MFA")).thenReturn(true);
        var context = new ServletOperatorRbacContext(
                request, jdbc, new byte[32], Duration.ofMinutes(10), Clock.fixed(NOW, ZoneOffset.UTC));
        when(jdbc.queryForList(anyString(), eq(context.digest("operator-subject"))))
                .thenReturn(List.of(Map.of(
                        "id", OPERATOR,
                        "status", "ACTIVE",
                        "mfa_enrolled_at", Timestamp.from(NOW.minusSeconds(1_000)),
                        "last_mfa_verified_at", Timestamp.from(NOW.minusSeconds(601)))));

        assertThat(context.current()).contains(
                new com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext(
                        OPERATOR, true, false));
    }
}
