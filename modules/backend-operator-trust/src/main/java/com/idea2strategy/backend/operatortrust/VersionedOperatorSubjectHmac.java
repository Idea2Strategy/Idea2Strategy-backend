package com.idea2strategy.backend.operatortrust;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VersionedOperatorSubjectHmac {
    private final OperatorTrustConfiguration configuration;

    public VersionedOperatorSubjectHmac(OperatorTrustConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public List<ProtectedOperatorSubject> protect(String issuer, String subject) {
        byte[] canonical = canonical(issuer, subject);
        return configuration.subjectHmacKeys().keySet().stream()
                .sorted(Comparator.comparing((Integer version) ->
                        version != configuration.currentSubjectHmacKeyVersion()).thenComparingInt(Integer::intValue))
                .map(version -> new ProtectedOperatorSubject(
                        version, digest(configuration.subjectHmacKey(version), canonical)))
                .toList();
    }

    static byte[] canonical(String issuer, String subject) {
        byte[] issuerBytes = required(issuer).getBytes(StandardCharsets.UTF_8);
        byte[] subjectBytes = required(subject).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES * 2 + issuerBytes.length + subjectBytes.length)
                .putInt(issuerBytes.length).put(issuerBytes)
                .putInt(subjectBytes.length).put(subjectBytes)
                .array();
    }

    private static String digest(byte[] key, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OPERATOR_SUBJECT_PROTECTION_UNAVAILABLE", exception);
        }
    }

    private static String required(String value) {
        Objects.requireNonNull(value, "identity component");
        if (value.isBlank()) throw new IllegalArgumentException("OPERATOR_IDENTITY_INVALID");
        return value;
    }
}
