package com.idea2strategy.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.operatortrust.OperatorPasswordHasher;
import com.idea2strategy.backend.operatortrust.OperatorSecretCipher;
import com.idea2strategy.backend.operatortrust.OperatorTotp;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

final class OperatorCredentialCommandSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OperatorPasswordHasher.Parameters PASSWORD_PARAMETERS =
            new OperatorPasswordHasher.Parameters(65536, 3, 1, 16, 32, 1);

    private OperatorCredentialCommandSupport() {}

    static ObjectNode execute(Arguments arguments, Map<String, String> environment, InputStream stdin, boolean reset) {
        arguments.rejectUnknown("--operator-id", "--login-name");
        UUID operatorId = uuid(arguments.required("--operator-id"), "--operator-id");
        String loginName = reset ? null : normalizeLogin(arguments.required("--login-name"));
        CredentialMaterial material = readAndVerify(operatorId, environment, stdin);
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    requiredEnvironment(environment, "I2S_BOOTSTRAP_JDBC_URL"),
                    requiredEnvironment(environment, "I2S_BOOTSTRAP_DB_USER"),
                    requiredEnvironment(environment, "I2S_BOOTSTRAP_DB_PASSWORD"));
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
            Result result = transaction.execute(status -> apply(jdbc, environment, operatorId, loginName, material, reset));
            return JSON.createObjectNode()
                    .put("operatorAccountId", operatorId.toString())
                    .put("credentialVersion", result.credentialVersion())
                    .put("auditEventId", result.auditEventId().toString())
                    .put("action", reset ? "OPERATOR_CREDENTIAL_RESET" : "OPERATOR_CREDENTIAL_PROVISION");
        } catch (CliFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CliFailure(5, "OPERATOR_CREDENTIAL_TRANSACTION_FAILED",
                    "Operator credential change was rejected");
        } finally {
            material.destroy();
        }
    }

    private static Result apply(JdbcTemplate jdbc, Map<String, String> environment, UUID operatorId,
            String loginName, CredentialMaterial material, boolean reset) {
        String expectedRole = requiredEnvironment(environment, "I2S_OPERATOR_CREDENTIAL_DB_ROLE");
        String actualRole = jdbc.queryForObject("select current_user", String.class);
        if (!expectedRole.equals(actualRole)) reject("OPERATOR_CREDENTIAL_DATABASE_ROLE_MISMATCH");
        Integer active = jdbc.queryForObject(
                "select count(*) from operations.operator_accounts where id = ? and status = 'ACTIVE'",
                Integer.class, operatorId);
        if (active == null || active != 1) reject("OPERATOR_CREDENTIAL_OPERATOR_NOT_ACTIVE");

        Long previousVersion = jdbc.query("select credential_version from operations.operator_login_credentials "
                        + "where operator_account_id = ? for update", rs -> rs.next() ? rs.getLong(1) : null, operatorId);
        if (reset && previousVersion == null) reject("OPERATOR_CREDENTIAL_NOT_PROVISIONED");
        if (!reset && previousVersion != null) reject("OPERATOR_CREDENTIAL_ALREADY_PROVISIONED");
        long nextVersion = reset ? Math.addExact(previousVersion, 1L) : 1L;
        Instant now = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        var encrypted = material.cipher().encrypt(operatorId, nextVersion, material.totpSeed());

        if (reset) {
            jdbc.update("""
                    update operations.operator_login_credentials
                       set password_hash = ?, password_parameters = cast(? as jsonb), password_version = 1,
                           credential_version = ?, totp_ciphertext = ?, totp_nonce = ?, totp_key_version = ?,
                           totp_enrolled_at = ?, last_accepted_totp_step = null, failed_attempt_count = 0,
                           locked_until = null, password_changed_at = ?, compromised_at = null, updated_at = ?
                     where operator_account_id = ? and credential_version = ?
                    """, material.passwordHash(), passwordParametersJson(), nextVersion,
                    encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(), Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now), operatorId, previousVersion);
        } else {
            jdbc.update("""
                    insert into operations.operator_login_credentials
                      (operator_account_id, login_name, password_hash, password_parameters, password_version,
                       credential_version, totp_ciphertext, totp_nonce, totp_key_version, totp_enrolled_at,
                       password_changed_at, created_at, updated_at)
                    values (?, ?, ?, cast(? as jsonb), 1, 1, ?, ?, ?, ?, ?, ?, ?)
                    """, operatorId, loginName, material.passwordHash(), passwordParametersJson(),
                    encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(), Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }

        jdbc.update("""
                update operations.operator_sessions
                   set revoked_at = ?, revocation_reason_code = 'CREDENTIAL_REPLACED'
                 where operator_account_id = ? and revoked_at is null
                """, Timestamp.from(now), operatorId);
        UUID auditId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID actorId = uuid(requiredEnvironment(environment, "I2S_OPERATOR_CREDENTIAL_ACTOR_ID"),
                "I2S_OPERATOR_CREDENTIAL_ACTOR_ID");
        String action = reset ? "OPERATOR_CREDENTIAL_RESET" : "OPERATOR_CREDENTIAL_PROVISION";
        jdbc.update("""
                insert into operations.audit_events
                  (id, actor_type, actor_id, action_type, target_domain, target_id, reason_code,
                   correlation_id, idempotency_key, occurred_at, decision_status, response_status, response_code,
                   request_document, response_document, before_document, after_document, evidence_document)
                values (?, 'DEPLOYMENT', ?, ?, 'OPERATOR_CREDENTIAL', ?, 'ADMINISTRATIVE_RECOVERY',
                        ?, ?, ?, 'SUCCEEDED', 200, ?,
                        jsonb_build_object('operatorAccountId', ?::text),
                        jsonb_build_object('operatorAccountId', ?::text, 'credentialVersion', ?::bigint,
                                           'sessionsRevoked', true),
                        jsonb_build_object('credentialVersion', ?::bigint),
                        jsonb_build_object('credentialVersion', ?::bigint),
                        jsonb_build_object('databaseRole', current_user))
                """, auditId, actorId, action, operatorId, correlationId,
                "operator-credential:" + auditId, Timestamp.from(now), action + "_SUCCEEDED",
                operatorId, operatorId, nextVersion, previousVersion == null ? 0L : previousVersion, nextVersion);
        return new Result(nextVersion, auditId);
    }

    private static CredentialMaterial readAndVerify(UUID operatorId, Map<String, String> environment, InputStream stdin) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
            char[] password = requiredSecret(reader.readLine(), "password").toCharArray();
            byte[] seed = Base64.getDecoder().decode(requiredSecret(reader.readLine(), "TOTP seed"));
            String code = requiredSecret(reader.readLine(), "TOTP confirmation code");
            if (new OperatorTotp().verify(seed, code, Instant.now(), -1).isEmpty()) {
                Arrays.fill(password, '\0');
                Arrays.fill(seed, (byte) 0);
                reject("OPERATOR_CREDENTIAL_TOTP_CONFIRMATION_INVALID");
            }
            String hash = new OperatorPasswordHasher(PASSWORD_PARAMETERS).hash(password);
            Arrays.fill(password, '\0');
            int keyVersion = Integer.parseInt(requiredEnvironment(environment, "OPERATOR_AUTH_TOTP_KEY_VERSION"));
            byte[] key = Base64.getDecoder().decode(requiredEnvironment(environment, "OPERATOR_AUTH_TOTP_KEY"));
            OperatorSecretCipher cipher = new OperatorSecretCipher(keyVersion,
                    Map.of(keyVersion, new SecretKeySpec(key, "AES")));
            Arrays.fill(key, (byte) 0);
            return new CredentialMaterial(hash, seed, cipher);
        } catch (CliFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CliFailure(5, "OPERATOR_CREDENTIAL_INPUT_INVALID", "Credential input is invalid");
        }
    }

    private static String passwordParametersJson() {
        return "{\"memoryKb\":65536,\"iterations\":3,\"parallelism\":1}";
    }

    private static String normalizeLogin(String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,119}")) {
            reject("OPERATOR_CREDENTIAL_LOGIN_INVALID");
        }
        return normalized;
    }

    private static UUID uuid(String value, String name) {
        try { return UUID.fromString(value); }
        catch (RuntimeException failure) { throw new CliFailure(5, "OPERATOR_CREDENTIAL_OPERATOR_ID_INVALID", name + " must be a UUID"); }
    }

    private static String requiredEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new CliFailure(5, "OPERATOR_CREDENTIAL_CONFIGURATION_MISSING", name + " is required");
        }
        return value;
    }

    private static String requiredSecret(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new CliFailure(5, "OPERATOR_CREDENTIAL_INPUT_REQUIRED", label + " is required on hidden standard input");
        }
        return value;
    }

    private static void reject(String code) {
        throw new CliFailure(5, code, "Operator credential change was rejected");
    }

    private record Result(long credentialVersion, UUID auditEventId) {}
    private record CredentialMaterial(String passwordHash, byte[] totpSeed, OperatorSecretCipher cipher) {
        void destroy() { Arrays.fill(totpSeed, (byte) 0); }
    }
}
