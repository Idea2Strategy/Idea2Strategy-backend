package com.idea2strategy.backend.api.operatorrbac;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadGuardCatalog;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadModels;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadPort;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadService;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperatorRbacReadControllerTest {
    private static final UUID ACTOR = UUID.fromString("a2200000-0000-4000-8000-000000000001");
    private static final UUID TARGET = UUID.fromString("a2200000-0000-4000-8000-000000000002");
    private static final UUID CATALOG_READ = UUID.fromString("a2200000-0000-4000-8000-000000000003");
    private static final UUID ASSIGNMENT_READ = UUID.fromString("a2200000-0000-4000-8000-000000000004");
    private static final UUID CORRELATION = UUID.fromString("a2200000-0000-4000-8000-000000000005");
    private static final Instant NOW = Instant.parse("2026-08-03T07:00:00Z");

    @Test
    void returnsOnlyTheAuthenticatedOperatorProjectionAndSignedMfaFreshness() throws Exception {
        mvc(context(true), Set.of(CATALOG_READ, ASSIGNMENT_READ), true)
                .perform(get("/api/v1/operations/me")
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.operatorId").value(ACTOR.toString()))
                .andExpect(jsonPath("$.view.currentMfa").value(true))
                .andExpect(jsonPath("$.view.mfaAuthenticatedAt").value(NOW.minusSeconds(60).toString()))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION.toString()));
    }

    @Test
    void missingAuthenticationReturnsCorrelationAware401() throws Exception {
        mvc(Optional::empty, Set.of(), false)
                .perform(get("/api/v1/operations/me").header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OPERATOR_AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION.toString()));
    }

    @Test
    void permissionAndMfaDenialsDoNotRevealWhetherTheTargetExists() throws Exception {
        mvc(context(false), Set.of(), false)
                .perform(get("/api/v1/operations/rbac/operators/{operatorId}/assignments", TARGET)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OPERATOR_RBAC_READ_FORBIDDEN"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION.toString()));
    }

    private static CurrentOperatorRbacContext context(boolean mfa) {
        return () -> Optional.of(new OperatorRequestContext(
                ACTOR, true, mfa, mfa ? NOW.minusSeconds(60) : null));
    }

    private static MockMvc mvc(
            CurrentOperatorRbacContext context, Set<UUID> permissions, boolean targetExists) {
        OperatorRbacReadPort port = new OperatorRbacReadPort() {
            @Override
            public OperatorRbacReadModels.ActorState loadActorState(UUID actorId, Instant at) {
                var self = new OperatorRbacReadModels.SelfView(
                        ACTOR, "catalog-v1", false, null, NOW.minusSeconds(60),
                        List.of(), List.of(), List.of());
                return new OperatorRbacReadModels.ActorState(true, "catalog-v1", permissions, self);
            }

            @Override
            public Optional<OperatorRbacReadModels.CatalogView> loadCatalog(String version, Instant at) {
                return Optional.of(new OperatorRbacReadModels.CatalogView(
                        version, List.of(), List.of(), List.of()));
            }

            @Override
            public Optional<OperatorRbacReadModels.AssignmentsView> loadAssignments(
                    UUID target, String version, Instant at) {
                return targetExists
                        ? Optional.of(new OperatorRbacReadModels.AssignmentsView(target, List.of()))
                        : Optional.empty();
            }

            @Override public void recordDecision(OperatorRbacReadModels.AuditDecision decision) {}
        };
        OperatorRbacReadGuardCatalog guards = () -> new OperatorRbacReadGuardCatalog.Guard(
                "catalog-v1", CATALOG_READ, true, ASSIGNMENT_READ, true);
        var service = new OperatorRbacReadService(port, guards, Clock.fixed(NOW, ZoneOffset.UTC));
        return MockMvcBuilders.standaloneSetup(new OperatorRbacReadController(service, context))
                .setControllerAdvice(new OperatorRbacExceptionHandler())
                .build();
    }
}
