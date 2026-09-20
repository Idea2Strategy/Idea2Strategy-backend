package com.idea2strategy.backend.operatortrust;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class OperatorTokenProtector {
    public enum Domain { SESSION, CSRF, SOURCE, LOGIN }

    private final Map<Domain, Integer> currentVersions;
    private final Map<Domain, Map<Integer, byte[]>> keys;
    private final SecureRandom random = new SecureRandom();

    public OperatorTokenProtector(int currentVersion, Map<Integer, byte[]> keys) {
        this(Map.of(Domain.SESSION, currentVersion, Domain.CSRF, currentVersion,
                        Domain.SOURCE, currentVersion, Domain.LOGIN, currentVersion),
                Map.of(Domain.SESSION, keys, Domain.CSRF, keys, Domain.SOURCE, keys, Domain.LOGIN, keys));
    }

    public OperatorTokenProtector(
            Map<Domain, Integer> currentVersions,
            Map<Domain, Map<Integer, byte[]>> domainKeys) {
        this.currentVersions = Map.copyOf(currentVersions);
        this.keys = domainKeys.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> entry.getValue().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, key -> key.getValue().clone()))));
        if (this.currentVersions.size() != Domain.values().length || this.keys.size() != Domain.values().length
                || java.util.Arrays.stream(Domain.values()).anyMatch(domain -> {
                    Integer version = this.currentVersions.get(domain);
                    Map<Integer, byte[]> ring = this.keys.get(domain);
                    return version == null || version <= 0 || ring == null || !ring.containsKey(version)
                            || ring.values().stream().anyMatch(key -> key.length < 32);
                })) {
            throw new IllegalArgumentException("OPERATOR_HMAC_KEY_CONFIGURATION_INVALID");
        }
    }

    public String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public ProtectedDigest digest(Domain domain, String value) {
        int version = currentVersions.get(domain);
        return new ProtectedDigest(version, hex(hmac(keys.get(domain).get(version), domain, value)));
    }

    public boolean matches(Domain domain, String value, ProtectedDigest expected) {
        byte[] key = keys.get(domain).get(expected.keyVersion());
        if (key == null) return false;
        byte[] actual = hmac(key, domain, value);
        byte[] stored;
        try { stored = HexFormat.of().parseHex(expected.hexDigest()); }
        catch (IllegalArgumentException malformed) { return false; }
        return MessageDigest.isEqual(actual, stored);
    }

    public CsrfToken deriveCsrf(String rawSessionToken, long generation) {
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmac(keys.get(Domain.CSRF).get(currentVersions.get(Domain.CSRF)),
                        Domain.CSRF, rawSessionToken + ":" + generation));
        return new CsrfToken(raw, digest(Domain.CSRF, raw));
    }

    private static byte[] hmac(byte[] key, Domain domain, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((byte) domain.ordinal());
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("OPERATOR_HMAC_UNAVAILABLE", unavailable);
        }
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

    public record ProtectedDigest(int keyVersion, String hexDigest) {}
    public record CsrfToken(String rawToken, ProtectedDigest digest) {}
}
