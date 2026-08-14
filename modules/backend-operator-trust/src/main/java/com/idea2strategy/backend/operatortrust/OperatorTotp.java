package com.idea2strategy.backend.operatortrust;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class OperatorTotp {
    private static final long PERIOD_SECONDS = 30;

    public OptionalLong verify(byte[] secret, String code, Instant now, long lastAcceptedStep) {
        if (code == null || !code.matches("[0-9]{6}")) return OptionalLong.empty();
        long current = Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
        for (long step = current - 1; step <= current + 1; step++) {
            if (step > lastAcceptedStep && constantTime(code(secret, step), code)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    public String currentCode(byte[] secret, Instant now) {
        return code(secret, Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS));
    }

    String code(byte[] secret, long step) {
        byte[] digest = step(secret, step);
        int offset = digest[digest.length - 1] & 0x0f;
        int binary = ((digest[offset] & 0x7f) << 24)
                | ((digest[offset + 1] & 0xff) << 16)
                | ((digest[offset + 2] & 0xff) << 8)
                | (digest[offset + 3] & 0xff);
        return "%06d".formatted(binary % 1_000_000);
    }

    private static byte[] step(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            return mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("OPERATOR_TOTP_UNAVAILABLE", unavailable);
        }
    }

    private static boolean constantTime(String left, String right) {
        int difference = left.length() ^ right.length();
        int size = Math.min(left.length(), right.length());
        for (int index = 0; index < size; index++) difference |= left.charAt(index) ^ right.charAt(index);
        return difference == 0;
    }
}
