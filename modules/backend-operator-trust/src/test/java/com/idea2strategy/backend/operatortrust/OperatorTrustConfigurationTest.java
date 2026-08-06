package com.idea2strategy.backend.operatortrust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class OperatorTrustConfigurationTest {
    @Test
    void exposesOnlyDeepCopiesOfHmacMaterial() {
        var configuration = OperatorTrustTestFixtures.configuration();
        byte[] one = configuration.subjectHmacKey(2);
        byte[] two = configuration.subjectHmacKeys().get(2);
        one[0] = 99;
        two[1] = 99;

        assertThat(configuration.subjectHmacKey(2)).containsOnly(2);
    }

    @Test
    void propertiesRejectNonRs256AndWeakOrAmbiguousRotationKeys() {
        OperatorTrustProperties properties = validProperties();
        properties.setAlgorithm("HS256");
        assertThatThrownBy(properties::validated).hasMessage("OPERATOR_TRUST_CONFIGURATION_INVALID");

        properties = validProperties();
        properties.setPreviousSubjectHmacKeyVersion(2);
        properties.setPreviousSubjectHmacKey(encoded(1));
        assertThatThrownBy(properties::validated).hasMessage("OPERATOR_TRUST_CONFIGURATION_INVALID");

        properties = validProperties();
        properties.setCurrentSubjectHmacKey(Base64.getEncoder().encodeToString(new byte[31]));
        assertThatThrownBy(properties::validated).hasMessage("OPERATOR_TRUST_CONFIGURATION_INVALID");
    }

    @Test
    void namespacedMfaClaimRequiresAnHttpsNameAndExactAllowedValue() {
        OperatorTrustProperties properties = validProperties();
        properties.setAllowedAmrValues(java.util.Set.of());
        properties.setMfaClaimName("https://ideatostrategy.com/claims/mfa");
        properties.setAllowedMfaClaimValues(java.util.Set.of("cognito:mfa-required"));
        assertThat(properties.validated().mfaClaimName())
                .isEqualTo("https://ideatostrategy.com/claims/mfa");

        properties.setMfaClaimName("mfa");
        assertThatThrownBy(properties::validated).hasMessage("OPERATOR_TRUST_CONFIGURATION_INVALID");

        properties = validProperties();
        properties.setAllowedAmrValues(java.util.Set.of());
        properties.setMfaClaimName("https://ideatostrategy.com/claims/mfa");
        properties.setAllowedMfaClaimValues(java.util.Set.of());
        assertThatThrownBy(properties::validated).hasMessage("OPERATOR_TRUST_CONFIGURATION_INVALID");

        properties = validProperties();
        properties.setMfaClaimName("https://example.com/claims/mfa");
        properties.setAllowedMfaClaimValues(java.util.Set.of("cognito:mfa-required"));
        assertThatThrownBy(properties::validated).hasMessage("OPERATOR_TRUST_CONFIGURATION_INVALID");

        properties = validProperties();
        properties.setMfaClaimName("https://ideatostrategy.com/claims/mfa");
        properties.setAllowedMfaClaimValues(java.util.Set.of("cognito:mfa-required", "other"));
        assertThatThrownBy(properties::validated).hasMessage("OPERATOR_TRUST_CONFIGURATION_INVALID");
    }

    private static OperatorTrustProperties validProperties() {
        OperatorTrustProperties value = new OperatorTrustProperties();
        value.setIssuer("https://operator.example");
        value.setJwkSetUri(java.net.URI.create("https://operator.example/jwks"));
        value.setAudience("operator-api");
        value.setAllowedAmrValues(java.util.Set.of("mfa"));
        value.setCurrentSubjectHmacKeyVersion(2);
        value.setCurrentSubjectHmacKey(encoded(2));
        return value;
    }

    private static String encoded(int marker) {
        return Base64.getEncoder().encodeToString(OperatorTrustTestFixtures.key(marker));
    }
}
