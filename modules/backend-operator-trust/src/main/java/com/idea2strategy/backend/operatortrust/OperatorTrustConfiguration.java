package com.idea2strategy.backend.operatortrust;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, validated deployment policy for the dedicated operator JWT. */
public record OperatorTrustConfiguration(
        String issuer,
        URI jwkSetUri,
        String audience,
        Duration maximumTokenAge,
        Duration maximumMfaAge,
        Duration clockSkew,
        Set<String> allowedAcrValues,
        Set<String> allowedAmrValues,
        String mfaClaimName,
        Set<String> allowedMfaClaimValues,
        Map<Integer, byte[]> subjectHmacKeys,
        int currentSubjectHmacKeyVersion) {

    public OperatorTrustConfiguration {
        issuer = required(issuer, "issuer");
        jwkSetUri = Objects.requireNonNull(jwkSetUri, "jwkSetUri");
        audience = required(audience, "audience");
        maximumTokenAge = positive(maximumTokenAge, "maximumTokenAge");
        maximumMfaAge = positive(maximumMfaAge, "maximumMfaAge");
        clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
        allowedAcrValues = clean(allowedAcrValues);
        allowedAmrValues = clean(allowedAmrValues);
        allowedMfaClaimValues = clean(allowedMfaClaimValues);
        mfaClaimName = optional(mfaClaimName);
        boolean customClaimConfigured = mfaClaimName != null || !allowedMfaClaimValues.isEmpty();
        if (!https(issuer) || !https(jwkSetUri.toString()) || clockSkew.isNegative()
                || clockSkew.compareTo(Duration.ofMinutes(1)) > 0
                || (allowedAcrValues.isEmpty() && allowedAmrValues.isEmpty()
                        && allowedMfaClaimValues.isEmpty())
                || (customClaimConfigured
                        && (!namespacedClaim(mfaClaimName) || allowedMfaClaimValues.isEmpty()))) {
            throw invalid();
        }
        var copiedKeys = new LinkedHashMap<Integer, byte[]>();
        Objects.requireNonNull(subjectHmacKeys, "subjectHmacKeys").forEach((version, key) -> {
            if (version == null || version < 1 || key == null || key.length < 32 || copiedKeys.size() >= 2) {
                throw invalid();
            }
            copiedKeys.put(version, key.clone());
        });
        if (copiedKeys.isEmpty() || !copiedKeys.containsKey(currentSubjectHmacKeyVersion)) {
            throw invalid();
        }
        subjectHmacKeys = Map.copyOf(copiedKeys);
    }

    public byte[] subjectHmacKey(int version) {
        byte[] key = subjectHmacKeys.get(version);
        return key == null ? null : key.clone();
    }

    @Override
    public Map<Integer, byte[]> subjectHmacKeys() {
        var copy = new LinkedHashMap<Integer, byte[]>();
        subjectHmacKeys.forEach((version, key) -> copy.put(version, key.clone()));
        return Map.copyOf(copy);
    }

    static byte[] decodeKey(String encoded) {
        try {
            return Base64.getDecoder().decode(required(encoded, "subjectHmacKey"));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static Set<String> clean(Set<String> values) {
        if (values == null) return Set.of();
        var result = new java.util.LinkedHashSet<String>();
        for (String value : values) result.add(required(value, "assuranceValue"));
        return Set.copyOf(result);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean namespacedClaim(String value) {
        if (value == null || "acr".equals(value) || "amr".equals(value)) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw invalid();
        return value;
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw invalid();
        return value.trim();
    }

    private static boolean https(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("OPERATOR_TRUST_CONFIGURATION_INVALID");
    }
}
