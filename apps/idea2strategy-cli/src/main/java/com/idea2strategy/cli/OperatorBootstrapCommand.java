package com.idea2strategy.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapManifest;
import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapRejectedException;
import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapService;
import com.idea2strategy.backend.persistence.operatorbootstrap.JdbcOperatorBootstrapAdapter;
import com.idea2strategy.backend.persistence.operatorbootstrap.OperatorBootstrapCredential;
import com.idea2strategy.backend.operatortrust.OperatorPasswordHasher;
import com.idea2strategy.backend.operatortrust.OperatorSecretCipher;
import com.idea2strategy.backend.operatortrust.OperatorTotp;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class OperatorBootstrapCommand {
    private static final int MAX_MANIFEST_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndRegisterModules();

    private OperatorBootstrapCommand() {}

    static ObjectNode execute(Arguments arguments, Map<String, String> environment, InputStream stdin) {
        arguments.rejectUnknown("--manifest", "--expected-sha256");
        try {
            byte[] bytes;
            try (InputStream input = Files.newInputStream(Path.of(arguments.required("--manifest")))) {
                bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_MANIFEST_BYTES) {
                throw new CliFailure(5, "OPERATOR_BOOTSTRAP_MANIFEST_SIZE_INVALID",
                        "Bootstrap manifest must be between 1 byte and 1 MiB");
            }
            String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String expectedHash = arguments.required("--expected-sha256");
            if (!expectedHash.matches("[0-9a-f]{64}")) {
                throw new CliFailure(5, "OPERATOR_BOOTSTRAP_EXPECTED_HASH_INVALID",
                        "Expected manifest hash must be 64 lowercase hexadecimal characters");
            }
            if (!MessageDigest.isEqual(actualHash.getBytes(StandardCharsets.US_ASCII),
                    expectedHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new CliFailure(5, "OPERATOR_BOOTSTRAP_MANIFEST_HASH_MISMATCH",
                        "Reviewed manifest hash does not match the supplied file");
            }
            OperatorBootstrapManifest manifest = JSON.readValue(bytes, OperatorBootstrapManifest.class);
            var reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
            char[] password = requiredSecret(reader.readLine(), "password").toCharArray();
            byte[] totpSeed = Base64.getDecoder().decode(requiredSecret(reader.readLine(), "TOTP seed"));
            String confirmationCode = requiredSecret(reader.readLine(), "TOTP confirmation code");
            if (new OperatorTotp().verify(totpSeed, confirmationCode, Instant.now(), -1).isEmpty()) {
                throw new CliFailure(5, "OPERATOR_BOOTSTRAP_TOTP_CONFIRMATION_INVALID", "TOTP confirmation failed");
            }
            var hasher = new OperatorPasswordHasher(new OperatorPasswordHasher.Parameters(65536, 3, 1, 16, 32, 1));
            String passwordHash = hasher.hash(password);
            java.util.Arrays.fill(password, '\0');
            int keyVersion = Integer.parseInt(requiredEnvironment(environment, "OPERATOR_AUTH_TOTP_KEY_VERSION"));
            byte[] key = Base64.getDecoder().decode(requiredEnvironment(environment, "OPERATOR_AUTH_TOTP_KEY"));
            var encrypted = new OperatorSecretCipher(keyVersion,
                    Map.of(keyVersion, new SecretKeySpec(key, "AES"))).encrypt(manifest.operatorAccountId(), 1, totpSeed);
            java.util.Arrays.fill(totpSeed, (byte) 0);
            var credential = new OperatorBootstrapCredential(passwordHash,
                    "{\"memoryKb\":65536,\"iterations\":3,\"parallelism\":1}", (short) 1,
                    encrypted.ciphertext(), encrypted.nonce(), (short) encrypted.keyVersion());
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    requiredEnvironment(environment, "I2S_BOOTSTRAP_JDBC_URL"),
                    requiredEnvironment(environment, "I2S_BOOTSTRAP_DB_USER"),
                    requiredEnvironment(environment, "I2S_BOOTSTRAP_DB_PASSWORD"));
            var adapter = new JdbcOperatorBootstrapAdapter(
                    new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource), credential);
            var result = new OperatorBootstrapService(adapter).execute(manifest, actualHash);
            ObjectNode response = JSON.createObjectNode()
                    .put("replayed", result.replayed())
                    .put("bootstrapKey", result.bootstrapKey())
                    .put("manifestHash", result.manifestHash())
                    .put("catalogVersion", result.catalogVersion())
                    .put("operatorAccountId", result.operatorAccountId().toString())
                    .put("operatorRoleAssignmentId", result.operatorRoleAssignmentId().toString())
                    .put("correlationId", result.correlationId().toString())
                    .put("auditEventId", result.auditEventId().toString())
                    .put("appliedAt", result.appliedAt().toString());
            return response;
        } catch (CliFailure failure) {
            throw failure;
        } catch (OperatorBootstrapRejectedException failure) {
            throw new CliFailure(5, failure.code(), "Operator bootstrap was rejected");
        } catch (Exception failure) {
            throw new CliFailure(5, "OPERATOR_BOOTSTRAP_MANIFEST_INVALID",
                    "Bootstrap manifest could not be verified or applied");
        }
    }

    private static String requiredEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new CliFailure(5, "OPERATOR_BOOTSTRAP_CONFIGURATION_MISSING", name + " is required");
        }
        return value;
    }

    private static String requiredSecret(String value, String label) {
        if (value == null || value.isBlank()) throw new CliFailure(5,
                "OPERATOR_BOOTSTRAP_CREDENTIAL_INPUT_REQUIRED", label + " is required on hidden standard input");
        return value;
    }
}
