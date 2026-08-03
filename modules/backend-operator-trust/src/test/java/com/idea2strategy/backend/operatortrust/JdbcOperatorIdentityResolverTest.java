package com.idea2strategy.backend.operatortrust;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcOperatorIdentityResolverTest {
    private static final UUID OPERATOR = UUID.fromString("a2200000-0000-4000-8000-000000000001");
    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-08-03T03:59:00Z");

    @Test
    void requiresExactlyOneActiveVersionedMappingAndUpdatesFreshMfaMonotonically() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(Map.of(
                "id", OPERATOR, "status", "ACTIVE", "external_identity_key_version", 2)));
        var resolver = resolver(jdbc);

        var result = resolver.resolve(new VerifiedOperatorJwt(
                "https://operator.example", "subject", AUTHENTICATED_AT, true));

        assertThat(result).get().satisfies(context -> {
            assertThat(context.operatorId()).isEqualTo(OPERATOR);
            assertThat(context.trustedExternalSubject()).isTrue();
            assertThat(context.mfaCompleted()).isTrue();
        });
        assertThat(jdbc.querySql).contains("external_identity_key_version = ?")
                .doesNotContain("is null");
        assertThat(jdbc.queryArguments).hasSize(4);
        assertThat(jdbc.updateArguments).containsExactly(
                Timestamp.from(AUTHENTICATED_AT), OPERATOR, Timestamp.from(AUTHENTICATED_AT));
        assertThat(jdbc.updateSql).contains("last_mfa_verified_at < ?");
    }

    @Test
    void missingDisabledAndAmbiguousMappingsFailWithoutMfaMutation() {
        List<List<Map<String, Object>>> cases = List.of(
                List.<Map<String, Object>>of(),
                List.of(Map.<String, Object>of(
                        "id", OPERATOR, "status", "DISABLED", "external_identity_key_version", 2)),
                List.of(
                        Map.<String, Object>of(
                                "id", OPERATOR, "status", "ACTIVE", "external_identity_key_version", 2),
                        Map.<String, Object>of(
                                "id", UUID.randomUUID(), "status", "ACTIVE", "external_identity_key_version", 1)));
        for (List<Map<String, Object>> rows : cases) {
            RecordingJdbc jdbc = new RecordingJdbc(rows);
            assertThat(resolver(jdbc).resolve(new VerifiedOperatorJwt(
                    "https://operator.example", "subject", AUTHENTICATED_AT, true))).isEmpty();
            assertThat(jdbc.updateSql).isNull();
        }
    }

    @Test
    void authenticatedOperatorWithoutCurrentMfaMapsButDoesNotRefreshAuditState() {
        RecordingJdbc jdbc = new RecordingJdbc(List.of(Map.of(
                "id", OPERATOR, "status", "ACTIVE", "external_identity_key_version", 2)));
        assertThat(resolver(jdbc).resolve(new VerifiedOperatorJwt(
                "https://operator.example", "subject", AUTHENTICATED_AT, false)))
                .get().extracting(context -> context.mfaCompleted()).isEqualTo(false);
        assertThat(jdbc.updateSql).isNull();
    }

    private static JdbcOperatorIdentityResolver resolver(JdbcTemplate jdbc) {
        return new JdbcOperatorIdentityResolver(jdbc,
                new VersionedOperatorSubjectHmac(OperatorTrustTestFixtures.configuration()));
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final List<Map<String, Object>> rows;
        private String querySql;
        private Object[] queryArguments;
        private String updateSql;
        private Object[] updateArguments;

        private RecordingJdbc(List<Map<String, Object>> rows) { this.rows = rows; }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            querySql = sql;
            queryArguments = args;
            return rows;
        }

        @Override
        public int update(String sql, Object... args) {
            updateSql = sql;
            updateArguments = args;
            return 1;
        }
    }
}
