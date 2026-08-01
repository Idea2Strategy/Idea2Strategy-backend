package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HmacOidcSubjectProtectorTest {
    @Test
    void bindsProviderIssuerAndSubjectWithoutReturningRawIdentityData() {
        var protector = new HmacOidcSubjectProtector(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), (short) 4);
        var principal = new VerifiedOidcPrincipal(
                "EXAMPLE", "https://issuer.example", "raw-subject", "person@example.com");

        var first = protector.protect(principal);
        var same = protector.protect(principal);
        var otherIssuer = protector.protect(new VerifiedOidcPrincipal(
                "EXAMPLE", "https://other.example", "raw-subject", "person@example.com"));

        assertThat(first).isEqualTo(same).isNotEqualTo(otherIssuer);
        assertThat(first.keyVersion()).isEqualTo((short) 4);
        assertThat(first.hmac()).doesNotContain("raw-subject", "person@example.com");
        assertThat(principal.toString()).doesNotContain("raw-subject", "person@example.com");
    }
}
