package com.idea2strategy.backend.admin;

import com.idea2strategy.backend.application.adminmcp.AdminMcpAuthorizationPort;
import com.idea2strategy.backend.application.adminmcp.AdminMcpExecutionPort;
import com.idea2strategy.backend.application.adminmcp.AdminMcpProviderPort;
import com.idea2strategy.backend.application.adminmcp.AdminMcpProviderRouter;
import com.idea2strategy.backend.application.adminmcp.AdminMcpService;
import com.idea2strategy.backend.application.adminmcp.AdminMcpToolRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AdminMcpBoundaryConfiguration {
    static final String REGISTRY_VERSION = "mcp-v1";
    static final UUID CORPORATE_ACTION_QUERY_PERMISSION =
            UUID.fromString("20000000-0000-4000-8000-000000000011");
    static final UUID CORPORATE_ACTION_APPROVE_PERMISSION =
            UUID.fromString("20000000-0000-4000-8000-000000000012");

    @Bean
    AdminMcpToolRegistry adminMcpToolRegistry() {
        AdminMcpToolRegistry.Tool query = tool(
                "corporate_action_candidate.query",
                AdminMcpToolRegistry.Capability.CORPORATE_ACTION_CANDIDATE_QUERY,
                AdminMcpToolRegistry.Mode.QUERY,
                CORPORATE_ACTION_QUERY_PERMISSION);
        AdminMcpToolRegistry.Tool approve = tool(
                "corporate_action_candidate.approve",
                AdminMcpToolRegistry.Capability.CORPORATE_ACTION_CANDIDATE_APPROVE,
                AdminMcpToolRegistry.Mode.APPROVAL,
                CORPORATE_ACTION_APPROVE_PERMISSION);
        return new AdminMcpToolRegistry(
                REGISTRY_VERSION,
                AdminMcpToolRegistry.Status.ACTIVE,
                Map.of(query.name(), query, approve.name(), approve));
    }

    @Bean
    AdminMcpProviderRouter adminMcpProviderRouter(List<AdminMcpProviderBinding> bindings) {
        Map<String, AdminMcpProviderPort> providers = new LinkedHashMap<>();
        for (AdminMcpProviderBinding binding : bindings) {
            if (providers.putIfAbsent(binding.targetDomain(), binding.provider()) != null) {
                throw new IllegalStateException(
                        "duplicate admin MCP provider domain: " + binding.targetDomain());
            }
        }
        Map<String, AdminMcpProviderPort> immutable = Map.copyOf(providers);
        return targetDomain -> java.util.Optional.ofNullable(immutable.get(targetDomain));
    }

    @Bean
    @ConditionalOnBean({AdminMcpAuthorizationPort.class, AdminMcpExecutionPort.class})
    AdminMcpService adminMcpService(
            AdminMcpToolRegistry registry,
            AdminMcpAuthorizationPort authorization,
            AdminMcpProviderRouter providers,
            AdminMcpExecutionPort executions) {
        return new AdminMcpService(
                registry, authorization, providers, executions, Clock.systemUTC());
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
}
