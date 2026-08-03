package com.idea2strategy.backend.operatortrust;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionedOperatorSubjectHmacTest {
    private final VersionedOperatorSubjectHmac protector =
            new VersionedOperatorSubjectHmac(OperatorTrustTestFixtures.configuration());

    @Test
    void emitsCurrentThenPreviousVersionWithoutExposingRawIdentity() {
        var protectedValues = protector.protect("https://operator.example", "subject-1");

        assertThat(protectedValues).extracting(ProtectedOperatorSubject::keyVersion)
                .containsExactly(2, 1);
        assertThat(protectedValues).allSatisfy(value -> {
            assertThat(value.digest()).matches("[0-9a-f]{64}");
            assertThat(value.digest()).doesNotContain("subject-1");
        });
    }

    @Test
    void lengthDelimitingAndIssuerBindingPreventConcatenationAndCrossIssuerCollisions() {
        assertThat(protector.protect("ab", "c").getFirst().digest())
                .isNotEqualTo(protector.protect("a", "bc").getFirst().digest())
                .isNotEqualTo(protector.protect("https://other.example", "c").getFirst().digest());
    }
}
