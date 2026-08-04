package com.idea2strategy.backend.application.adminmcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AdminMcpServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T16:00:00Z");
    private static final UUID OPERATOR_ID = uuid(1);
    private static final UUID QUERY_PERMISSION = uuid(11);
    private static final UUID APPROVE_PERMISSION = uuid(12);
    private static final String QUERY_TOOL = "corporate_action_candidate.query";
    private static final String APPROVE_TOOL = "corporate_action_candidate.approve";

    @Test
    void routesAnActiveVersionedToolThroughItsExactPermissionAndTargetMapping() {
        var fixture = fixture();
        fixture.provider.result = succeeded(Map.of(), Map.of("candidateId", "candidate-1", "version", 7L));

        AdminMcpExecutionResult result = fixture.service.invoke(query("query-1", hash('a')));

        assertThat(result.status()).isEqualTo(AdminMcpExecutionResult.Status.RETURNED);
        assertThat(result.response()).containsEntry("candidateId", "candidate-1");
        assertThat(fixture.authorization.calls).containsExactly(
                new AuthorizationCall(QUERY_PERMISSION, "CORPORATE_ACTION"));
        assertThat(fixture.provider.requests).singleElement().satisfies(request -> {
            assertThat(request.toolName()).isEqualTo(QUERY_TOOL);
            assertThat(request.targetDomain()).isEqualTo("CORPORATE_ACTION");
        });
        assertThat(result.auditEvidence().registryVersion()).isEqualTo("mcp-v1");
        assertThat(result.auditEvidence().rbacCatalogVersion()).isEqualTo("rbac-v1");
    }

    @Test
    void leavesUntrustedSubjectsAtTheSecurityBoundaryAndPersistsTrustedGuardDenials() {
        var fixture = fixture();
        AdminMcpInvocation untrusted = invocation(
                QUERY_TOOL, "query-untrusted", hash('b'), null,
                new OperatorRequestContext(OPERATOR_ID, false, true), "schema-v1", "mcp-v1");

        assertThatThrownBy(() -> fixture.service.invoke(untrusted))
                .isInstanceOf(AdminMcpAuthenticationRejectedException.class)
                .hasMessage("ADMIN_MCP_AUTHENTICATION_REQUIRED");
        assertThat(fixture.executions.committed).isEmpty();

        fixture.authorization.decision = AdminMcpAuthorizationPort.Decision.rejected(
                "OPERATOR_MFA_REQUIRED", "rbac-v1");
        AdminMcpExecutionResult denied = fixture.service.invoke(query("query-no-mfa", hash('c')));

        assertThat(denied.status()).isEqualTo(AdminMcpExecutionResult.Status.REJECTED);
        assertThat(denied.code()).isEqualTo("OPERATOR_MFA_REQUIRED");
        assertThat(fixture.executions.committed).containsExactly(denied);
        assertThat(fixture.provider.requests).isEmpty();
    }

    @Test
    void rejectsUnknownToolRegistryVersionAndSchemaBeforeAuthorizationOrProviderCalls() {
        var fixture = fixture();

        AdminMcpExecutionResult unknown = fixture.service.invoke(invocation(
                "unknown.tool", "unknown", hash('d'), null, trusted(), "schema-v1", "mcp-v1"));
        AdminMcpExecutionResult staleRegistry = fixture.service.invoke(invocation(
                QUERY_TOOL, "stale-registry", hash('e'), null, trusted(), "schema-v1", "mcp-v0"));
        AdminMcpExecutionResult staleSchema = fixture.service.invoke(invocation(
                QUERY_TOOL, "stale-schema", hash('f'), null, trusted(), "schema-v0", "mcp-v1"));
        AdminMcpInvocation extraInput = new AdminMcpInvocation(
                trusted(), "mcp-v1", QUERY_TOOL, "schema-v1", "candidate-1", null,
                Map.of("candidateId", "candidate-1", "privateSource", "secret"),
                uuid(31), "extra-input", hash('1'));
        AdminMcpExecutionResult schemaSmuggling = fixture.service.invoke(extraInput);

        assertThat(List.of(unknown.code(), staleRegistry.code(), staleSchema.code(), schemaSmuggling.code()))
                .containsExactly(
                        "MCP_TOOL_NOT_ALLOWED",
                        "MCP_REGISTRY_VERSION_UNAVAILABLE",
                        "MCP_REQUEST_SCHEMA_REJECTED",
                        "MCP_REQUEST_SCHEMA_REJECTED");
        assertThat(fixture.authorization.calls).isEmpty();
        assertThat(fixture.provider.requests).isEmpty();
    }

    @Test
    void refusesForbiddenCapabilitiesAtRegistryConstruction() {
        for (AdminMcpToolRegistry.Capability capability : List.of(
                AdminMcpToolRegistry.Capability.STRATEGY_CREATE,
                AdminMcpToolRegistry.Capability.PRIVATE_STRATEGY_SOURCE_READ,
                AdminMcpToolRegistry.Capability.USER_ORDER_MUTATION)) {
            var forbidden = tool("forbidden." + capability.name().toLowerCase(), capability,
                    AdminMcpToolRegistry.Mode.APPROVAL, APPROVE_PERMISSION);

            assertThatThrownBy(() -> new AdminMcpToolRegistry(
                            "mcp-v1", AdminMcpToolRegistry.Status.ACTIVE, Map.of(forbidden.name(), forbidden)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("forbidden tool");
        }
    }

    @Test
    void requiresDecidedContentHashAndKeepsStaleApprovalsRejectedWithAuditEvidence() {
        var fixture = fixture();
        AdminMcpExecutionResult missingVersion = fixture.service.invoke(invocation(
                APPROVE_TOOL, "approve-no-version", hash('2'), null,
                trusted(), "schema-v1", "mcp-v1"));

        fixture.provider.result = new AdminMcpProviderPort.Result(
                AdminMcpProviderPort.Result.Status.REJECTED,
                "STALE_CONTENT_HASH",
                Map.of("candidateId", "candidate-1", "version", 8L),
                Map.of("candidateId", "candidate-1", "version", 8L));
        AdminMcpExecutionResult stale = fixture.service.invoke(approve("approve-stale", hash('3'), hash('7')));

        assertThat(missingVersion.code()).isEqualTo("MCP_DECIDED_CONTENT_HASH_REQUIRED");
        assertThat(stale.status()).isEqualTo(AdminMcpExecutionResult.Status.REJECTED);
        assertThat(stale.code()).isEqualTo("STALE_CONTENT_HASH");
        assertThat(stale.auditEvidence().before()).containsEntry("version", 8L);
        assertThat(stale.auditEvidence().after()).containsEntry("version", 8L);
    }

    @Test
    void failsClosedOnMissingProviderTimeoutAndAmbiguousProviderResult() {
        var missing = fixture();
        missing.router.available = false;
        assertThat(missing.service.invoke(query("missing-provider", hash('4'))).code())
                .isEqualTo("MCP_PROVIDER_UNAVAILABLE");

        var timeout = fixture();
        timeout.provider.result = providerResult(AdminMcpProviderPort.Result.Status.TIMEOUT, "TIMEOUT");
        assertThat(timeout.service.invoke(query("timeout", hash('5'))).code())
                .isEqualTo("MCP_PROVIDER_TIMEOUT");

        var unknown = fixture();
        unknown.provider.result = providerResult(AdminMcpProviderPort.Result.Status.UNKNOWN, "AMBIGUOUS");
        assertThat(unknown.service.invoke(query("ambiguous", hash('6'))).code())
                .isEqualTo("MCP_PROVIDER_RESULT_UNKNOWN");

        assertThat(timeout.executions.committed).allMatch(
                result -> result.status() == AdminMcpExecutionResult.Status.REJECTED);
        assertThat(unknown.executions.committed).allMatch(
                result -> result.status() == AdminMcpExecutionResult.Status.REJECTED);
    }

    @Test
    void rejectsUnexpectedPrivateProviderFieldsWithoutReturningOrAuditingThem() {
        var fixture = fixture();
        fixture.provider.result = succeeded(
                Map.of(),
                Map.of("candidateId", "candidate-1", "version", 7L, "privateSource", "secret"));

        AdminMcpExecutionResult result = fixture.service.invoke(query("private-output", hash('7')));

        assertThat(result.code()).isEqualTo("MCP_PROVIDER_RESPONSE_SCHEMA_REJECTED");
        assertThat(result.response()).isEmpty();
        assertThat(result.auditEvidence().before()).isEmpty();
        assertThat(result.auditEvidence().after()).isEmpty();
    }

    @Test
    void appliesAnApprovalOnlyWithCompleteAllowlistedBeforeAfterEvidence() {
        var fixture = fixture();
        fixture.provider.result = succeeded(
                Map.of("candidateId", "candidate-1", "version", 7L, "status", "PENDING"),
                Map.of("candidateId", "candidate-1", "version", 8L, "status", "APPROVED"));

        AdminMcpExecutionResult result = fixture.service.invoke(approve("approve-1", hash('8'), hash('7')));

        assertThat(result.status()).isEqualTo(AdminMcpExecutionResult.Status.APPLIED);
        assertThat(result.auditEvidence().before()).containsEntry("status", "PENDING");
        assertThat(result.auditEvidence().after()).containsEntry("status", "APPROVED");
        assertThat(fixture.provider.requests).singleElement()
                .extracting(AdminMcpProviderPort.Request::decidedContentHash)
                .isEqualTo(hash('7'));
    }

    @Test
    void replaysOneIdempotentDecisionAndRejectsAHashConflict() {
        var fixture = fixture();
        fixture.provider.result = succeeded(Map.of(), Map.of("candidateId", "candidate-1", "version", 7L));
        AdminMcpInvocation invocation = query("global-key", hash('9'));

        AdminMcpExecutionResult first = fixture.service.invoke(invocation);
        AdminMcpExecutionResult replay = fixture.service.invoke(invocation);

        assertThat(replay).isEqualTo(first);
        assertThat(fixture.executions.decisions).isEqualTo(1);
        assertThat(fixture.provider.requests).hasSize(1);
        assertThatThrownBy(() -> fixture.service.invoke(query("global-key", hash('0'))))
                .isInstanceOf(AdminMcpIdempotencyConflictException.class)
                .hasMessage("ADMIN_MCP_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void serializesConcurrentDuplicateApprovalsBeforeCallingTheProvider() throws Exception {
        var fixture = fixture();
        fixture.provider.result = succeeded(
                Map.of("candidateId", "candidate-1", "version", 7L, "status", "PENDING"),
                Map.of("candidateId", "candidate-1", "version", 8L, "status", "APPROVED"));
        AdminMcpInvocation invocation = approve("concurrent", hash('a'), hash('7'));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> fixture.service.invoke(invocation));
            var second = executor.submit(() -> fixture.service.invoke(invocation));
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
        assertThat(fixture.executions.decisions).isEqualTo(1);
        assertThat(fixture.provider.requests).hasSize(1);
        assertThat(fixture.executions.committed).hasSize(1);
    }

    private static Fixture fixture() {
        var authorization = new RecordingAuthorization();
        var provider = new RecordingProvider();
        var router = new RecordingRouter(provider);
        var executions = new RecordingExecutionPort();
        var service = new AdminMcpService(
                registry(), authorization, router, executions, Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, authorization, provider, router, executions);
    }

    private static AdminMcpToolRegistry registry() {
        AdminMcpToolRegistry.Tool query = tool(
                QUERY_TOOL,
                AdminMcpToolRegistry.Capability.CORPORATE_ACTION_CANDIDATE_QUERY,
                AdminMcpToolRegistry.Mode.QUERY,
                QUERY_PERMISSION);
        AdminMcpToolRegistry.Tool approve = tool(
                APPROVE_TOOL,
                AdminMcpToolRegistry.Capability.CORPORATE_ACTION_CANDIDATE_APPROVE,
                AdminMcpToolRegistry.Mode.APPROVAL,
                APPROVE_PERMISSION);
        return new AdminMcpToolRegistry(
                "mcp-v1", AdminMcpToolRegistry.Status.ACTIVE,
                Map.of(query.name(), query, approve.name(), approve));
    }

    private static AdminMcpToolRegistry.Tool tool(
            String name,
            AdminMcpToolRegistry.Capability capability,
            AdminMcpToolRegistry.Mode mode,
            UUID permissionId) {
        return new AdminMcpToolRegistry.Tool(
                name,
                capability,
                mode,
                permissionId,
                "CORPORATE_ACTION",
                "schema-v1",
                Set.of("candidateId"),
                Set.of("candidateId"),
                Set.of("candidateId", "version", "status"));
    }

    private static AdminMcpInvocation query(String key, String hash) {
        return invocation(QUERY_TOOL, key, hash, null, trusted(), "schema-v1", "mcp-v1");
    }

    private static AdminMcpInvocation approve(String key, String hash, String decidedContentHash) {
        return invocation(APPROVE_TOOL, key, hash, decidedContentHash, trusted(), "schema-v1", "mcp-v1");
    }

    private static AdminMcpInvocation invocation(
            String tool,
            String key,
            String hash,
            String decidedContentHash,
            OperatorRequestContext context,
            String schemaVersion,
            String registryVersion) {
        return new AdminMcpInvocation(
                context,
                registryVersion,
                tool,
                schemaVersion,
                "candidate-1",
                decidedContentHash,
                Map.of("candidateId", "candidate-1"),
                uuid(21),
                key,
                hash);
    }

    private static OperatorRequestContext trusted() {
        return new OperatorRequestContext(OPERATOR_ID, true, true);
    }

    private static AdminMcpProviderPort.Result succeeded(
            Map<String, Object> before, Map<String, Object> after) {
        return new AdminMcpProviderPort.Result(
                AdminMcpProviderPort.Result.Status.SUCCEEDED, "OK", before, after);
    }

    private static AdminMcpProviderPort.Result providerResult(
            AdminMcpProviderPort.Result.Status status, String code) {
        return new AdminMcpProviderPort.Result(status, code, Map.of(), Map.of());
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("20000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private record Fixture(
            AdminMcpService service,
            RecordingAuthorization authorization,
            RecordingProvider provider,
            RecordingRouter router,
            RecordingExecutionPort executions) {}

    private record AuthorizationCall(UUID permissionId, String targetDomain) {}

    private static final class RecordingAuthorization implements AdminMcpAuthorizationPort {
        private Decision decision = Decision.granted("rbac-v1");
        private final List<AuthorizationCall> calls = new ArrayList<>();

        @Override
        public Decision authorize(
                OperatorRequestContext requestContext,
                UUID requiredPermissionId,
                String targetDomain,
                Instant evaluatedAt) {
            calls.add(new AuthorizationCall(requiredPermissionId, targetDomain));
            return decision;
        }
    }

    private static final class RecordingProvider implements AdminMcpProviderPort {
        private Result result = providerResult(Result.Status.UNKNOWN, "NOT_CONFIGURED");
        private final List<Request> requests = new ArrayList<>();

        @Override
        public synchronized Result invoke(Request request) {
            requests.add(request);
            return result;
        }
    }

    private static final class RecordingRouter implements AdminMcpProviderRouter {
        private final AdminMcpProviderPort provider;
        private boolean available = true;

        private RecordingRouter(AdminMcpProviderPort provider) {
            this.provider = provider;
        }

        @Override
        public Optional<AdminMcpProviderPort> providerFor(String targetDomain) {
            return available ? Optional.of(provider) : Optional.empty();
        }
    }

    private static final class RecordingExecutionPort implements AdminMcpExecutionPort {
        private final Map<String, Receipt> receipts = new HashMap<>();
        private final List<AdminMcpExecutionResult> committed = new ArrayList<>();
        private int decisions;

        @Override
        public synchronized AdminMcpExecutionResult executeIdempotently(
                AdminMcpInvocation invocation,
                Instant evaluatedAt,
                Decision decision) {
            Receipt receipt = receipts.get(invocation.idempotencyKey());
            if (receipt != null) {
                if (!receipt.requestHash.equals(invocation.requestHash())) {
                    throw new AdminMcpIdempotencyConflictException();
                }
                return receipt.result;
            }
            decisions++;
            AdminMcpExecutionResult result = decision.decide();
            receipts.put(invocation.idempotencyKey(), new Receipt(invocation.requestHash(), result));
            committed.add(result);
            return result;
        }
    }

    private record Receipt(String requestHash, AdminMcpExecutionResult result) {}
}
