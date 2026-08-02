package com.idea2strategy.backend.application.adminmcp;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AdminMcpService {
    private final AdminMcpToolRegistry registry;
    private final AdminMcpAuthorizationPort authorization;
    private final AdminMcpProviderRouter providers;
    private final AdminMcpExecutionPort executions;
    private final Clock clock;

    public AdminMcpService(
            AdminMcpToolRegistry registry,
            AdminMcpAuthorizationPort authorization,
            AdminMcpProviderRouter providers,
            AdminMcpExecutionPort executions,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AdminMcpExecutionResult invoke(AdminMcpInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        if (!invocation.requestContext().trustedExternalSubject()) {
            throw new AdminMcpAuthenticationRejectedException();
        }
        Instant now = clock.instant();
        return executions.executeIdempotently(invocation, now, () -> decide(invocation, now));
    }

    private AdminMcpExecutionResult decide(AdminMcpInvocation invocation, Instant now) {
        if (registry.status() != AdminMcpToolRegistry.Status.ACTIVE
                || !registry.version().equals(invocation.registryVersion())) {
            return rejected(invocation, now, "MCP_REGISTRY_VERSION_UNAVAILABLE", null, null);
        }
        AdminMcpToolRegistry.Tool tool = registry.tools().get(invocation.toolName());
        if (tool == null || tool.capability().forbidden()) {
            return rejected(invocation, now, "MCP_TOOL_NOT_ALLOWED", null, null);
        }
        if (!tool.requestSchemaVersion().equals(invocation.requestSchemaVersion())
                || !invocation.input().keySet().containsAll(tool.requiredInputFields())
                || !tool.allowedInputFields().containsAll(invocation.input().keySet())) {
            return rejected(invocation, now, "MCP_REQUEST_SCHEMA_REJECTED", null, tool);
        }
        if (tool.mode() == AdminMcpToolRegistry.Mode.APPROVAL && invocation.targetVersion() == null) {
            return rejected(invocation, now, "MCP_TARGET_VERSION_REQUIRED", null, tool);
        }

        AdminMcpAuthorizationPort.Decision guard = authorization.authorize(
                invocation.requestContext(), tool.permissionId(), tool.targetDomain(), now);
        if (!guard.granted()) {
            return rejected(invocation, now, guard.code(), guard, tool);
        }

        Optional<AdminMcpProviderPort> provider = providers.providerFor(tool.targetDomain());
        if (provider.isEmpty()) {
            return rejected(invocation, now, "MCP_PROVIDER_UNAVAILABLE", guard, tool);
        }
        AdminMcpProviderPort.Result providerResult = provider.orElseThrow().invoke(new AdminMcpProviderPort.Request(
                tool.name(), tool.targetDomain(), invocation.targetId(), invocation.targetVersion(),
                invocation.input(), invocation.idempotencyKey()));
        if (providerResult.status() == AdminMcpProviderPort.Result.Status.TIMEOUT) {
            return rejected(invocation, now, "MCP_PROVIDER_TIMEOUT", guard, tool);
        }
        if (providerResult.status() == AdminMcpProviderPort.Result.Status.UNKNOWN) {
            return rejected(invocation, now, "MCP_PROVIDER_RESULT_UNKNOWN", guard, tool);
        }
        if (providerResult.status() == AdminMcpProviderPort.Result.Status.REJECTED) {
            return rejected(invocation, now, providerResult.code(), guard, tool,
                    allowlisted(providerResult.before(), tool), allowlisted(providerResult.after(), tool));
        }
        if (!tool.allowedOutputFields().containsAll(providerResult.before().keySet())
                || !tool.allowedOutputFields().containsAll(providerResult.after().keySet())) {
            return rejected(invocation, now, "MCP_PROVIDER_RESPONSE_SCHEMA_REJECTED", guard, tool);
        }
        if (tool.mode() == AdminMcpToolRegistry.Mode.APPROVAL
                && (providerResult.before().isEmpty() || providerResult.after().isEmpty())) {
            return rejected(invocation, now, "MCP_APPROVAL_EVIDENCE_INCOMPLETE", guard, tool);
        }
        AdminMcpExecutionResult.Status status = tool.mode() == AdminMcpToolRegistry.Mode.APPROVAL
                ? AdminMcpExecutionResult.Status.APPLIED
                : AdminMcpExecutionResult.Status.RETURNED;
        return new AdminMcpExecutionResult(
                status,
                providerResult.code(),
                Map.copyOf(providerResult.after()),
                evidence(invocation, now, providerResult.code(), guard, tool,
                        providerResult.before(), providerResult.after()));
    }

    private static Map<String, Object> allowlisted(
            Map<String, Object> document, AdminMcpToolRegistry.Tool tool) {
        return document.entrySet().stream()
                .filter(entry -> tool.allowedOutputFields().contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static AdminMcpExecutionResult rejected(
            AdminMcpInvocation invocation,
            Instant now,
            String code,
            AdminMcpAuthorizationPort.Decision guard,
            AdminMcpToolRegistry.Tool tool) {
        return rejected(invocation, now, code, guard, tool, Map.of(), Map.of());
    }

    private static AdminMcpExecutionResult rejected(
            AdminMcpInvocation invocation,
            Instant now,
            String code,
            AdminMcpAuthorizationPort.Decision guard,
            AdminMcpToolRegistry.Tool tool,
            Map<String, Object> before,
            Map<String, Object> after) {
        return new AdminMcpExecutionResult(
                AdminMcpExecutionResult.Status.REJECTED,
                code,
                Map.of(),
                evidence(invocation, now, code, guard, tool, before, after));
    }

    private static AdminMcpExecutionResult.AuditEvidence evidence(
            AdminMcpInvocation invocation,
            Instant now,
            String code,
            AdminMcpAuthorizationPort.Decision guard,
            AdminMcpToolRegistry.Tool tool,
            Map<String, Object> before,
            Map<String, Object> after) {
        return new AdminMcpExecutionResult.AuditEvidence(
                invocation.requestContext().operatorId(),
                invocation.registryVersion(),
                guard == null ? null : guard.rbacCatalogVersion(),
                invocation.toolName(),
                tool == null ? "UNRESOLVED" : tool.targetDomain(),
                invocation.targetId(),
                invocation.targetVersion(),
                code,
                invocation.correlationId(),
                now,
                before,
                after);
    }
}
