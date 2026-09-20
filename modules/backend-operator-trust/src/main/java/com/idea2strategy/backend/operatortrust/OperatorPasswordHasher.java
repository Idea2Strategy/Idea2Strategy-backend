package com.idea2strategy.backend.operatortrust;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

public final class OperatorPasswordHasher {
    private final Parameters parameters;
    private final SecureRandom random;

    public OperatorPasswordHasher(Parameters parameters) {
        this(parameters, new SecureRandom());
    }

    OperatorPasswordHasher(Parameters parameters, SecureRandom random) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.random = Objects.requireNonNull(random, "random");
    }

    public String hash(char[] password) {
        byte[] salt = new byte[parameters.saltBytes()];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, parameters);
        try {
            return "$argon2id$v=19$m=" + parameters.memoryKb() + ",t=" + parameters.iterations()
                    + ",p=" + parameters.parallelism() + "$" + Base64.getEncoder().withoutPadding().encodeToString(salt)
                    + "$" + Base64.getEncoder().withoutPadding().encodeToString(hash);
        } finally {
            Arrays.fill(hash, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    public boolean verify(char[] password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 6 || !"argon2id".equals(parts[1]) || !"v=19".equals(parts[2])) return false;
            String[] values = parts[3].split(",");
            Parameters stored = new Parameters(
                    Integer.parseInt(values[0].substring(2)),
                    Integer.parseInt(values[1].substring(2)),
                    Integer.parseInt(values[2].substring(2)),
                    Base64.getDecoder().decode(parts[4]).length,
                    Base64.getDecoder().decode(parts[5]).length,
                    parameters.version());
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);
            byte[] actual = derive(password, salt, stored);
            try {
                return MessageDigest.isEqual(expected, actual);
            } finally {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(expected, (byte) 0);
                Arrays.fill(actual, (byte) 0);
            }
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    public boolean needsRehash(String encoded) {
        String marker = "m=" + parameters.memoryKb() + ",t=" + parameters.iterations()
                + ",p=" + parameters.parallelism();
        return encoded == null || !encoded.contains(marker);
    }

    private static byte[] derive(char[] password, byte[] salt, Parameters parameters) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        byte[] output = new byte[parameters.hashBytes()];
        try {
            var generator = new Argon2BytesGenerator();
            generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(parameters.memoryKb())
                    .withIterations(parameters.iterations())
                    .withParallelism(parameters.parallelism())
                    .withSalt(salt)
                    .build());
            generator.generateBytes(bytes, output);
            return output;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public record Parameters(int memoryKb, int iterations, int parallelism, int saltBytes, int hashBytes, int version) {
        public Parameters {
            if (memoryKb < 8192 || iterations < 1 || parallelism < 1 || saltBytes < 16 || hashBytes < 32 || version < 1) {
                throw new IllegalArgumentException("OPERATOR_PASSWORD_PARAMETERS_INVALID");
            }
        }
    }
}
