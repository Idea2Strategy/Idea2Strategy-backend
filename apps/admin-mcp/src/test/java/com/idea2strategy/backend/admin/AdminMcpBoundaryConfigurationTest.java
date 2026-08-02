package com.idea2strategy.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.adminmcp.AdminMcpProviderPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminMcpBoundaryConfigurationTest {
    private final AdminMcpBoundaryConfiguration configuration = new AdminMcpBoundaryConfiguration();

    @Test
    void exposesOnlyTheExistingVersionedCorporateActionFixtureAndExactPermissionMatrix() {
        var registry = configuration.adminMcpToolRegistry();

        assertThat(registry.version()).isEqualTo("mcp-v1");
        assertThat(registry.tools()).containsOnlyKeys(
                "corporate_action_candidate.query", "corporate_action_candidate.approve");
        assertThat(registry.tools().get("corporate_action_candidate.query").permissionId())
                .isEqualTo(AdminMcpBoundaryConfiguration.CORPORATE_ACTION_QUERY_PERMISSION);
        assertThat(registry.tools().get("corporate_action_candidate.approve").permissionId())
                .isEqualTo(AdminMcpBoundaryConfiguration.CORPORATE_ACTION_APPROVE_PERMISSION);
        assertThat(registry.tools().keySet()).noneMatch(name ->
                name.contains("strategy") || name.contains("order") || name.contains("private"));
    }

    @Test
    void routesOneExplicitProviderPerDomainAndRejectsAmbiguousBindings() {
        AdminMcpProviderPort provider = request -> new AdminMcpProviderPort.Result(
                AdminMcpProviderPort.Result.Status.SUCCEEDED, "OK", Map.of(), Map.of());
        var binding = new AdminMcpProviderBinding("CORPORATE_ACTION", provider);

        assertThat(configuration.adminMcpProviderRouter(List.of(binding))
                .providerFor("CORPORATE_ACTION")).contains(provider);
        assertThat(configuration.adminMcpProviderRouter(List.of(binding))
                .providerFor("UNKNOWN")).isEmpty();
        assertThatThrownBy(() -> configuration.adminMcpProviderRouter(List.of(binding, binding)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }
}
