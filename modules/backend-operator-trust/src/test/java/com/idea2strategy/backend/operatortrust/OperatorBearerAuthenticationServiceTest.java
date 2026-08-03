package com.idea2strategy.backend.operatortrust;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtException;

class OperatorBearerAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T04:00:00Z");
    private static final UUID CORRELATION = UUID.fromString("a2200000-0000-4000-8000-000000000001");

    @Test
    void composesDecodeAssuranceAndFreshDatabaseMapping() {
        UUID operator = UUID.randomUUID();
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                return List.of(Map.of(
                        "id", operator, "status", "ACTIVE", "external_identity_key_version", 2));
            }
            @Override public int update(String sql, Object... args) { return 1; }
        };
        var service = service(token -> OperatorJwtAssuranceTest.jwt(Map.of(
                "amr", List.of("mfa"), "auth_time", NOW.minusSeconds(60).getEpochSecond())), jdbc);

        assertThat(service.authenticate("signed-jwt", CORRELATION))
                .get().extracting(context -> context.operatorId()).isEqualTo(operator);
    }

    @Test
    void malformedDecoderFailureBlankAndDatabaseFailureAllFailClosed() {
        JdbcTemplate unused = new JdbcTemplate();
        assertThat(service(token -> { throw new JwtException("bad token"); }, unused)
                .authenticate("bad", CORRELATION)).isEmpty();
        assertThat(service(token -> OperatorJwtAssuranceTest.jwt(Map.of()), unused)
                .authenticate(" ", CORRELATION)).isEmpty();
        JdbcTemplate failing = new JdbcTemplate() {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                throw new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
            }
        };
        assertThat(service(token -> OperatorJwtAssuranceTest.jwt(Map.of()), failing)
                .authenticate("signed-jwt", CORRELATION)).isEmpty();
    }

    private static OperatorBearerAuthenticationService service(
            org.springframework.security.oauth2.jwt.JwtDecoder decoder, JdbcTemplate jdbc) {
        var configuration = OperatorTrustTestFixtures.configuration();
        return new OperatorBearerAuthenticationService(
                decoder,
                new OperatorJwtAssurance(configuration, Clock.fixed(NOW, ZoneOffset.UTC)),
                new JdbcOperatorIdentityResolver(jdbc, new VersionedOperatorSubjectHmac(configuration)),
                (correlationId, reasonCode) -> {});
    }
}
