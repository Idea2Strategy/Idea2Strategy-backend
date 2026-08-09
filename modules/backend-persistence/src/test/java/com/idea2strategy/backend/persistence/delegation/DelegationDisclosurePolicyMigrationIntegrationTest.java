package com.idea2strategy.backend.persistence.delegation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Guards the disclosure a delegation is required to point at.
 *
 * <p>`identity.delegated_authorizations.disclosure_policy_document_id` is NOT NULL, so this
 * document is not decoration: if the policy code resolves to nothing, delegation does not degrade,
 * it stops. Both ways that has already happened are pinned here.
 *
 * <p>Publishing v2 with a future `published_at` retired v1 while v2 was not yet selectable and left
 * the code with zero current documents — every grant failed. And publishing the disclosure as
 * `is_required` put it in the set every account must consent to before it can finish
 * authenticating, which blocked login for customers who never delegate anything. Neither failure
 * is visible in the migration file being reviewed; both are visible here.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = DelegationDisclosurePolicyMigrationIntegrationTest.TestApplication.class)
class DelegationDisclosurePolicyMigrationIntegrationTest {
    private static final String POLICY_CODE = "delegation.strategy-edit.disclosure";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void resolvesToExactlyOneCurrentDocument() {
        List<Map<String, Object>> current = jdbc.queryForList(
                "select version, is_required from identity.policy_documents "
                        + "where policy_code = ? and retired_at is null and published_at <= now()",
                POLICY_CODE);

        assertThat(current)
                .describedAs("a delegation cannot be granted without a current disclosure")
                .hasSize(1);
    }

    /**
     * Consent to this document is asked for when a delegation is granted. Requiring it would make
     * every customer answer for a feature most of them never use, before they can log in.
     */
    @Test
    void isNeverPartOfTheRequiredConsentSet() {
        Boolean required = jdbc.queryForObject(
                "select bool_or(is_required) from identity.policy_documents where policy_code = ?",
                Boolean.class,
                POLICY_CODE);

        assertThat(required).isFalse();
    }

    /** The hash is what a later reader trusts to tell whether the text changed underneath them. */
    @Test
    void storesAHashThatMatchesTheStoredText() {
        Boolean matches = jdbc.queryForObject(
                "select bool_and(content_hash = encode(sha256(convert_to(content_text, 'UTF8')), 'hex')) "
                        + "from identity.policy_documents where policy_code = ?",
                Boolean.class,
                POLICY_CODE);

        assertThat(matches).isTrue();
    }

    /**
     * The delegation can create trade containers, not only edit blocks inside one the customer
     * already shaped. A disclosure that omits that understates what is being delegated, which is
     * the single thing this document exists to prevent.
     */
    @Test
    void tellsTheCustomerTheToolCanBuildTheStrategyItself() {
        String text = jdbc.queryForObject(
                "select content_text from identity.policy_documents "
                        + "where policy_code = ? and retired_at is null and published_at <= now()",
                String.class,
                POLICY_CODE);

        assertThat(text).contains("컨테이너");
        assertThat(text).contains("주문", "출시");
    }

    @SpringBootApplication
    static class TestApplication {}
}
