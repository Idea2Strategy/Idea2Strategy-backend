package com.idea2strategy.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.adminmcp.AdminMcpAuthorizationPort;
import com.idea2strategy.backend.application.adminmcp.AdminMcpExecutionPort;
import com.idea2strategy.backend.application.adminmcp.AdminMcpExecutionResult;
import com.idea2strategy.backend.application.adminmcp.AdminMcpIdempotencyConflictException;
import com.idea2strategy.backend.application.adminmcp.AdminMcpInvocation;
import com.idea2strategy.backend.application.adminmcp.AdminMcpProviderPort;
import com.idea2strategy.backend.application.adminmcp.AdminMcpProviderRouter;
import com.idea2strategy.backend.application.adminmcp.AdminMcpService;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminMcpControllerTest {
    private static final UUID OPERATOR = id(1);
    private static final UUID CORRELATION = id(2);
    private static final Instant NOW = Instant.parse("2026-08-02T16:00:00Z");

    @Test
    void invokesTheVersionedApprovalBoundaryAndReplaysWithoutASecondProviderEffect() throws Exception {
        Fixture fixture = fixture(AdminMcpProviderPort.Result.Status.SUCCEEDED);
        String body = """
                {"registryVersion":"mcp-v1","requestSchemaVersion":"schema-v1",
                 "targetId":"10000000-0000-4000-8000-000000000001",
                 "decidedContentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "input":{"candidateId":"10000000-0000-4000-8000-000000000001",
                          "decision":"APPROVE","evidenceBindings":["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],
                          "aggregateSequence":1}}
                """;

        for (int attempt = 0; attempt < 2; attempt++) {
            fixture.mvc.perform(post("/mcp/v1/tools/corporate_action_candidate.approve:invoke")
                            .header("Idempotency-Key", "approval-1")
                            .header("X-Correlation-Id", CORRELATION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPLIED"))
                    .andExpect(jsonPath("$.result.decision").value("APPROVE"))
                    .andExpect(jsonPath("$.result.decidedContentHash").value("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                    .andExpect(jsonPath("$.result.permissionId").value(AdminMcpBoundaryConfiguration.CORPORATE_ACTION_APPROVE_PERMISSION.toString()))
                    .andExpect(jsonPath("$.correlationId").value(CORRELATION.toString()));
        }

        assertThat(fixture.provider.calls).isOne();
        assertThat(fixture.provider.last.decidedContentHash()).isEqualTo("a".repeat(64));
        assertThat(fixture.authorization.permission)
                .isEqualTo(AdminMcpBoundaryConfiguration.CORPORATE_ACTION_APPROVE_PERMISSION);
    }

    @Test
    void mapsProviderTimeoutToFailClosedServiceUnavailableWithoutLeakingProviderData() throws Exception {
        Fixture fixture = fixture(AdminMcpProviderPort.Result.Status.TIMEOUT);

        fixture.mvc.perform(post("/mcp/v1/tools/corporate_action_candidate.query:invoke")
                        .header("Idempotency-Key", "query-timeout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registryVersion":"mcp-v1","requestSchemaVersion":"schema-v1",
                                 "targetId":"candidate-1",
                                 "input":{"candidateId":"candidate-1"}}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.code").value("MCP_PROVIDER_TIMEOUT"))
                .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    void rejectsUntrustedSubjectAndUnknownOrSmuggledToolsBeforeProviderInvocation() throws Exception {
        Fixture untrusted = fixture(AdminMcpProviderPort.Result.Status.SUCCEEDED, false);
        untrusted.mvc.perform(post("/mcp/v1/tools/corporate_action_candidate.query:invoke")
                        .header("Idempotency-Key", "untrusted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("candidateId", "candidate-1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("ADMIN_MCP_AUTHENTICATION_REQUIRED"));

        Fixture trusted = fixture(AdminMcpProviderPort.Result.Status.SUCCEEDED);
        trusted.mvc.perform(post("/mcp/v1/tools/user_order.execute:invoke")
                        .header("Idempotency-Key", "forbidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("candidateId", "candidate-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MCP_TOOL_NOT_ALLOWED"));
        trusted.mvc.perform(post("/mcp/v1/tools/corporate_action_candidate.query:invoke")
                        .header("Idempotency-Key", "smuggled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("privateSource", "secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MCP_REQUEST_SCHEMA_REJECTED"));
        assertThat(trusted.provider.calls).isZero();
    }

    private static String requestBody(String key, String value) {
        return """
                {"registryVersion":"mcp-v1","requestSchemaVersion":"schema-v1",
                 "targetId":"candidate-1","input":{"%s":"%s"}}
                """.formatted(key, value);
    }

    private static Fixture fixture(AdminMcpProviderPort.Result.Status status) {
        return fixture(status, true);
    }

    private static Fixture fixture(AdminMcpProviderPort.Result.Status status, boolean trusted) {
        AdminMcpBoundaryConfiguration configuration = new AdminMcpBoundaryConfiguration();
        RecordingAuthorization authorization = new RecordingAuthorization();
        RecordingProvider provider = new RecordingProvider(status);
        AdminMcpProviderRouter router = targetDomain -> Optional.of(provider);
        MemoryExecutions executions = new MemoryExecutions();
        AdminMcpService service = new AdminMcpService(
                configuration.adminMcpToolRegistry(), authorization, router, executions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        OperatorRequestContext actor = new OperatorRequestContext(OPERATOR, trusted, true);
        AdminMcpController controller = new AdminMcpController(
                service, () -> Optional.of(actor), new ObjectMapper());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AdminMcpExceptionHandler())
                .build();
        return new Fixture(mvc, provider, authorization);
    }

    private record Fixture(
            MockMvc mvc, RecordingProvider provider, RecordingAuthorization authorization) {}

    private static final class RecordingAuthorization implements AdminMcpAuthorizationPort {
        private UUID permission;

        @Override
        public Decision authorize(
                OperatorRequestContext requestContext,
                UUID requiredPermissionId,
                String targetDomain,
                Instant evaluatedAt) {
            permission = requiredPermissionId;
            return Decision.granted("rbac-v1");
        }
    }

    private static final class RecordingProvider implements AdminMcpProviderPort {
        private final Result.Status status;
        private int calls;
        private Request last;

        private RecordingProvider(Result.Status status) {
            this.status = status;
        }

        @Override
        public Result invoke(Request request) {
            calls++;
            last = request;
            if (status == Result.Status.TIMEOUT) {
                return new Result(status, "TRANSPORT_TIMEOUT", Map.of("privateSource", "secret"), Map.of());
            }
            return new Result(status, "APPROVED",
                    Map.of("candidateId", request.targetId(), "decision", "APPROVE", "status", "PENDING"),
                    Map.of(
                            "candidateId", request.targetId(),
                            "decision", "APPROVE",
                            "decidedContentHash", request.decidedContentHash(),
                            "evidenceBindings", request.input().get("evidenceBindings"),
                            "aggregateSequence", request.input().get("aggregateSequence"),
                            "status", "APPROVED"));
        }
    }

    private static final class MemoryExecutions implements AdminMcpExecutionPort {
        private final Map<String, Receipt> receipts = new HashMap<>();

        @Override
        public synchronized AdminMcpExecutionResult executeIdempotently(
                AdminMcpInvocation invocation, Instant evaluatedAt, Decision decision) {
            Receipt prior = receipts.get(invocation.idempotencyKey());
            if (prior != null) {
                if (!prior.hash.equals(invocation.requestHash())) {
                    throw new AdminMcpIdempotencyConflictException();
                }
                return prior.result;
            }
            AdminMcpExecutionResult result = decision.decide();
            receipts.put(invocation.idempotencyKey(), new Receipt(invocation.requestHash(), result));
            return result;
        }
    }

    private record Receipt(String hash, AdminMcpExecutionResult result) {}

    private static UUID id(long suffix) {
        return UUID.fromString("a1600000-0000-4000-8000-%012d".formatted(suffix));
    }
}
