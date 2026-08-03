package com.idea2strategy.backend.operatortrust;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Performs a fresh, non-cached operator mapping lookup for every request. */
public final class JdbcOperatorIdentityResolver {
    private final JdbcTemplate jdbc;
    private final VersionedOperatorSubjectHmac subjects;

    public JdbcOperatorIdentityResolver(JdbcTemplate jdbc, VersionedOperatorSubjectHmac subjects) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.subjects = Objects.requireNonNull(subjects, "subjects");
    }

    public Optional<OperatorRequestContext> resolve(VerifiedOperatorJwt identity) {
        Objects.requireNonNull(identity, "identity");
        List<ProtectedOperatorSubject> candidates = subjects.protect(identity.issuer(), identity.subject());
        String predicates = String.join(" or ", java.util.Collections.nCopies(
                candidates.size(), "(external_identity_key_version = ? and external_identity_key_hmac = ?)"));
        List<Object> arguments = new ArrayList<>(candidates.size() * 2);
        for (ProtectedOperatorSubject candidate : candidates) {
            arguments.add(candidate.keyVersion());
            arguments.add(candidate.digest());
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status, external_identity_key_version
                from operations.operator_accounts
                where %s
                """.formatted(predicates), arguments.toArray());
        if (rows.size() != 1 || !"ACTIVE".equals(rows.getFirst().get("status"))) {
            return Optional.empty();
        }
        UUID operatorId = (UUID) rows.getFirst().get("id");
        if (identity.currentMfa() && identity.authenticatedAt() != null) {
            Timestamp authenticatedAt = Timestamp.from(identity.authenticatedAt());
            jdbc.update("""
                    update operations.operator_accounts
                    set last_mfa_verified_at = ?
                    where id = ? and status = 'ACTIVE'
                      and (last_mfa_verified_at is null or last_mfa_verified_at < ?)
                    """, authenticatedAt, operatorId, authenticatedAt);
        }
        return Optional.of(new OperatorRequestContext(
                operatorId,
                true,
                identity.currentMfa(),
                identity.currentMfa() ? identity.authenticatedAt() : null));
    }
}
