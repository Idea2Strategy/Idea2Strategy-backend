package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OidcStepUpNonceMigrationContractTest {
    @Test
    void storesOnlyDigestPinnedSingleUseOidcChallenges() throws Exception {
        String sql;
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802060300__backend_oidc_step_up_nonces.sql")) {
            assertTrue(stream != null, "OIDC nonce migration must exist");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("CREATE TABLE identity.oidc_step_up_nonces"));
        assertTrue(sql.contains("nonce_digest varchar(128)"));
        assertTrue(sql.contains("consumed_by_account_id uuid"));
        assertTrue(sql.contains("consumed_at IS NULL"));
        assertTrue(sql.contains("verification_attempt_count BETWEEN 0 AND 5"));
        assertTrue(sql.contains("expires_at > requested_at"));
        assertTrue(sql.contains("oidc_step_up_nonce_provider_expiry_idx"));
        assertTrue(!sql.contains("nonce_plaintext"));
    }
}
