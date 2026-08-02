package com.idea2strategy.backend.api.operatorrbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacApiGuardCatalog;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommand;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommandService;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacDecision;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacResult;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperatorRbacControllerTest {
    private static final UUID ACTOR = UUID.fromString("a1300000-0000-4000-8000-000000000001");
    private static final UUID TARGET = UUID.fromString("a1300000-0000-4000-8000-000000000002");
    private static final UUID ROLE = UUID.fromString("a1300000-0000-4000-8000-000000000003");
    private static final UUID GRANT_PERMISSION = UUID.fromString("a1300000-0000-4000-8000-000000000004");
    private static final UUID REVOKE_PERMISSION = UUID.fromString("a1300000-0000-4000-8000-000000000005");
    private static final UUID CORRELATION = UUID.fromString("a1300000-0000-4000-8000-000000000006");
    private static final Instant NOW = Instant.parse("2026-08-03T01:00:00Z");

    @Test
    void resolvesTheTrustedActorAndServerOwnedCatalogGuardForGrant() throws Exception {
        AtomicReference<OperatorRbacCommand> captured = new AtomicReference<>();
        OperatorRbacCommandService service = new OperatorRbacCommandService((command, at, decision) -> {
            captured.set(command);
            return new OperatorRbacResult(OperatorRbacResult.DecisionStatus.NO_OP,
                    "ROLE_ALREADY_ASSIGNED", null, evidence());
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        mvc(service, () -> Optional.of(new OperatorRequestContext(ACTOR, true, true)))
                .perform(post("/api/v1/operations/rbac/assignments/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetOperatorId":"%s","roleId":"%s","reasonCode":"REVIEW_APPROVED",
                                 "correlationId":"%s","idempotencyKey":"grant-1","requestHash":"%s"}
                                """.formatted(TARGET, ROLE, CORRELATION, "a".repeat(64))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ROLE_ALREADY_ASSIGNED"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION.toString()));

        assertThat(captured.get().requestContext().operatorId()).isEqualTo(ACTOR);
        assertThat(captured.get().requiredPermissionId()).isEqualTo(GRANT_PERMISSION);
        assertThat(captured.get().expectedCatalogVersion()).isEqualTo("catalog-v1");
    }

    @Test
    void failsClosedBeforeTheCommandPortWhenTheExternalSubjectIsMissing() throws Exception {
        OperatorRbacCommandService service = new OperatorRbacCommandService((command, at, decision) -> {
            throw new AssertionError("unauthenticated request must not reach persistence");
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        mvc(service, Optional::empty)
                .perform(post("/api/v1/operations/rbac/assignments/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetOperatorId":"%s","roleId":"%s","reasonCode":"REVIEW_APPROVED",
                                 "correlationId":"%s","idempotencyKey":"grant-2","requestHash":"%s"}
                                """.formatted(TARGET, ROLE, CORRELATION, "b".repeat(64))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OPERATOR_AUTHENTICATION_REQUIRED"));
    }

    private static MockMvc mvc(OperatorRbacCommandService service, CurrentOperatorRbacContext context) {
        OperatorRbacApiGuardCatalog guards = () -> new OperatorRbacApiGuardCatalog.Guard(
                "catalog-v1", GRANT_PERMISSION, REVOKE_PERMISSION);
        return MockMvcBuilders.standaloneSetup(new OperatorRbacController(service, context, guards))
                .setControllerAdvice(new OperatorRbacExceptionHandler())
                .build();
    }

    private static OperatorRbacDecision.Evidence evidence() {
        return new OperatorRbacDecision.Evidence(
                "catalog-v1", Set.of(), Set.of(), Set.of(), Set.of(), true, true, true);
    }
}
