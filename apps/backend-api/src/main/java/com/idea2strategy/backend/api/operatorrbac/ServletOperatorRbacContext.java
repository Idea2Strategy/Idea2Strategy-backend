package com.idea2strategy.backend.api.operatorrbac;

import com.idea2strategy.backend.application.operatorrbac.CurrentOperatorRbacContext;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;

/** Maps a container-authenticated servlet principal onto an active operator account. */
public class ServletOperatorRbacContext implements CurrentOperatorRbacContext {
    private final HttpServletRequest request;
    private final JdbcTemplate jdbc;
    private final byte[] subjectHmacKey;
    private final Duration maximumMfaAge;
    private final Clock clock;

    public ServletOperatorRbacContext(
            HttpServletRequest request,
            JdbcTemplate jdbc,
            byte[] subjectHmacKey,
            Duration maximumMfaAge,
            Clock clock) {
        this.request = request;
        this.jdbc = jdbc;
        this.subjectHmacKey = subjectHmacKey.clone();
        this.maximumMfaAge = maximumMfaAge;
        this.clock = clock;
        if (subjectHmacKey.length < 32 || maximumMfaAge.isZero() || maximumMfaAge.isNegative()) {
            throw new IllegalArgumentException("OPERATOR_AUTH_CONFIGURATION_INVALID");
        }
    }

    @Override
    public Optional<OperatorRequestContext> current() {
        if (request.getUserPrincipal() == null || request.getUserPrincipal().getName().isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status, mfa_enrolled_at, last_mfa_verified_at
                from operations.operator_accounts where external_identity_key_hmac = ?
                """, digest(request.getUserPrincipal().getName()));
        if (rows.size() != 1 || !"ACTIVE".equals(rows.getFirst().get("status"))) {
            return Optional.empty();
        }
        Instant verifiedAt = instant(rows.getFirst().get("last_mfa_verified_at"));
        boolean mfa = request.isUserInRole("MFA")
                && rows.getFirst().get("mfa_enrolled_at") != null
                && verifiedAt != null
                && !verifiedAt.isBefore(clock.instant().minus(maximumMfaAge));
        return Optional.of(new OperatorRequestContext(
                (java.util.UUID) rows.getFirst().get("id"), true, mfa));
    }

    String digest(String subject) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(subjectHmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OPERATOR_SUBJECT_PROTECTION_UNAVAILABLE", exception);
        }
    }

    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        return null;
    }
}
