package com.idea2strategy.backend.api.operatorrbac;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OperatorCognitoMfaConfigurationTest {
    @Test
    void applicationEnvironmentLeavesCognitoMfaClaimDisabledUnlessDeploymentOptsIn() throws IOException {
        try (var stream = getClass().getResourceAsStream("/application.yaml")) {
            assertThat(stream).isNotNull();
            String yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml)
                    .contains("mfa-claim-name: ${OPERATOR_AUTH_MFA_CLAIM_NAME:}")
                    .contains("allowed-mfa-claim-values: ${OPERATOR_AUTH_ALLOWED_MFA_CLAIM_VALUES:}");
        }
    }
}
