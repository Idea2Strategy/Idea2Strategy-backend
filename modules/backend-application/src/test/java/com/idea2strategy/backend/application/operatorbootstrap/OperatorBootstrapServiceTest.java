package com.idea2strategy.backend.application.operatorbootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperatorBootstrapServiceTest {
    @Test
    void rejectsNonHexIdentityDigest() {
        assertThatThrownBy(() -> service().execute(manifest("not-a-digest", id(1)), "a".repeat(64)))
                .isInstanceOf(OperatorBootstrapRejectedException.class)
                .hasMessage("OPERATOR_BOOTSTRAP_MANIFEST_INVALID");
    }

    @Test
    void rejectsInitialRoleOutsideReviewedManifest() {
        assertThatThrownBy(() -> service().execute(manifest("e".repeat(64), id(99)), "a".repeat(64)))
                .isInstanceOf(OperatorBootstrapRejectedException.class)
                .hasMessage("OPERATOR_BOOTSTRAP_OUT_OF_MANIFEST");
    }

    private static OperatorBootstrapService service() {
        return new OperatorBootstrapService((manifest, hash) -> { throw new AssertionError("port called"); });
    }

    private static OperatorBootstrapManifest manifest(String digest, UUID initialRole) {
        UUID role = id(1); UUID permission = id(2);
        return new OperatorBootstrapManifest("bootstrap", "catalog", "d".repeat(64), "bootstrap_role",
                (short) 1, digest, id(3), id(4), initialRole, id(5), "review-ticket-1", id(6), id(7),
                List.of(new OperatorBootstrapManifest.Role(role, "ROOT", 100)),
                List.of(new OperatorBootstrapManifest.Permission(permission, "READ", "Read", "HIGH")),
                List.of(new OperatorBootstrapManifest.RolePermission(role, permission, true)));
    }

    private static UUID id(long value) { return new UUID(0, value); }
}
