package com.idea2strategy.backend.api.operatorauth;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.operatortrust.OperatorAuthenticationRejectedException;
import com.idea2strategy.backend.operatortrust.OperatorSessionService;
import com.idea2strategy.backend.operatortrust.OperatorTrustProperties;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperatorAuthControllerTest {
    OperatorSessionService sessions;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessions = org.mockito.Mockito.mock(OperatorSessionService.class);
        var properties = new OperatorTrustProperties();
        properties.setSecureCookie(true);
        mvc = MockMvcBuilders.standaloneSetup(new OperatorAuthController(sessions, properties))
                .setControllerAdvice(new OperatorAuthExceptionHandler())
                .build();
    }

    @Test
    void loginIssuesTheExactProductionHostCookieAndNoStoreResponse() throws Exception {
        when(sessions.login(org.mockito.ArgumentMatchers.eq("admin"), org.mockito.ArgumentMatchers.any(char[].class),
                org.mockito.ArgumentMatchers.eq("123456"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new OperatorSessionService.IssuedSession(UUID.randomUUID(), UUID.randomUUID(),
                        "raw-session", "raw-csrf", Instant.parse("2026-08-14T00:00:00Z"),
                        Instant.parse("2026-08-14T00:15:00Z"), Instant.parse("2026-08-14T08:00:00Z")));

        mvc.perform(post("/api/v1/operator-auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"admin\",\"password\":\"password\",\"totpCode\":\"123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().value("__Host-operator_session", "raw-session"))
                .andExpect(cookie().secure("__Host-operator_session", true))
                .andExpect(cookie().httpOnly("__Host-operator_session", true))
                .andExpect(cookie().sameSite("__Host-operator_session", "Strict"))
                .andExpect(jsonPath("$.csrfToken").value("raw-csrf"));
    }

    @Test
    void allCredentialFailuresUseTheSamePublicResponse() throws Exception {
        when(sessions.login(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(char[].class),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new OperatorAuthenticationRejectedException());

        mvc.perform(post("/api/v1/operator-auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"unknown\",\"password\":\"wrong\",\"totpCode\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("OPERATOR_AUTHENTICATION_REJECTED"));
    }

    @Test
    void sessionInspectionRequiresTheOpaqueCookie() throws Exception {
        mvc.perform(get("/api/v1/operator-auth/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OPERATOR_AUTHENTICATION_REJECTED"));
    }
}
