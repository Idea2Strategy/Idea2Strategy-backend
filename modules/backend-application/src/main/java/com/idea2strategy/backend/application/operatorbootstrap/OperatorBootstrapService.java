package com.idea2strategy.backend.application.operatorbootstrap;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class OperatorBootstrapService {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private final OperatorBootstrapPort port;

    public OperatorBootstrapService(OperatorBootstrapPort port) {
        this.port = Objects.requireNonNull(port);
    }

    public OperatorBootstrapResult execute(OperatorBootstrapManifest manifest, String manifestHash) {
        validate(manifest, manifestHash);
        return port.apply(manifest, manifestHash);
    }

    private static void validate(OperatorBootstrapManifest manifest, String manifestHash) {
        if (manifest == null || !validText(manifest.bootstrapKey(), 160)
                || !validText(manifest.catalogVersion(), 80)
                || !HASH.matcher(nullToEmpty(manifest.catalogContentHash())).matches()
                || !validText(manifest.expectedDatabaseRole(), 63)
                || !HASH.matcher(nullToEmpty(manifestHash)).matches()
                || manifest.externalIdentityKeyVersion() <= 0
                || !HASH.matcher(nullToEmpty(manifest.externalIdentityKeyHmac())).matches()
                || manifest.operatorAccountId() == null
                || manifest.operatorRoleAssignmentId() == null || manifest.initialRoleId() == null
                || manifest.deploymentActorId() == null || !validText(manifest.grantProvenance(), 160)
                || manifest.correlationId() == null || manifest.auditEventId() == null
                || manifest.roles().isEmpty()
                || manifest.permissions().isEmpty() || manifest.rolePermissions().isEmpty()) {
            reject("OPERATOR_BOOTSTRAP_MANIFEST_INVALID");
        }
        Set<UUID> roleIds = new HashSet<>();
        Set<String> roleCodes = new HashSet<>();
        for (var role : manifest.roles()) {
            if (role.id() == null || !validText(role.code(), 80) || role.hierarchyRank() < 0
                    || !roleIds.add(role.id()) || !roleCodes.add(role.code())) {
                reject("OPERATOR_BOOTSTRAP_MANIFEST_INVALID");
            }
        }
        Set<UUID> permissionIds = new HashSet<>();
        Set<String> permissionCodes = new HashSet<>();
        for (var permission : manifest.permissions()) {
            if (permission.id() == null || !validText(permission.code(), 120)
                    || !validText(permission.description(), 500) || !validText(permission.sensitivity(), 30)
                    || !permissionIds.add(permission.id()) || !permissionCodes.add(permission.code())) {
                reject("OPERATOR_BOOTSTRAP_MANIFEST_INVALID");
            }
        }
        if (!roleIds.contains(manifest.initialRoleId())) reject("OPERATOR_BOOTSTRAP_OUT_OF_MANIFEST");
        Set<String> mappings = new HashSet<>();
        for (var mapping : manifest.rolePermissions()) {
            if (!roleIds.contains(mapping.roleId()) || !permissionIds.contains(mapping.permissionId())) {
                reject("OPERATOR_BOOTSTRAP_OUT_OF_MANIFEST");
            }
            if (!mappings.add(mapping.roleId() + ":" + mapping.permissionId())) {
                reject("OPERATOR_BOOTSTRAP_MANIFEST_INVALID");
            }
        }
    }

    private static boolean validText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void reject(String code) {
        throw new OperatorBootstrapRejectedException(code);
    }
}
