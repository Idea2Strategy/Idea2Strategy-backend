package com.idea2strategy.backend.api.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.api.identity.PasswordResetEmailRequested;
import com.idea2strategy.backend.api.identity.VerificationEmailRequested;
import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import com.idea2strategy.backend.application.notification.NotificationRequest;
import com.idea2strategy.backend.operatortrust.VersionedOperatorSubjectHmac;
import com.idea2strategy.backend.persistence.notification.EmailDeliveryGateway;
import com.idea2strategy.backend.persistence.notification.NotificationEmailWorker;
import com.idea2strategy.backend.persistence.notification.NotificationEventConsumer;
import com.idea2strategy.backend.persistence.notification.NotificationPersistenceAdapter;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A22 full A journey over the real {@code BackendApiApplication} and PostgreSQL.
 *
 * <p>Journey 1 walks the account lifecycle that A07~A12 fixed: signup, email verification,
 * login, password-reset recovery, recovery-code recovery, withdrawal request/cancellation,
 * scheduler-driven dormancy transition, and password step-up reactivation.
 *
 * <p>Journey 2 walks the operations half that A13~A20 fixed, through the newly activated
 * production wiring: an operator (bootstrapped catalog + RBAC assignment, authenticated by
 * the verified operator JWT boundary) applies an account sanction over HTTP, the sanctioned
 * user appeals through the user-case API, the operator works the appeal queue
 * (assign, start review, release sanction), and the staged case notification flows through
 * the A17 transactional outbox claim/receipt into the A18 notification store and email
 * delivery worker, ending visible on the user's notification feed. Redeliveries replay.
 */
@Testcontainers(disabledWithoutDocker = true)
@RecordApplicationEvents
@SpringBootTest
@Import(AccountOperationsFullJourneyIntegrationTest.OperatorJwtTestConfiguration.class)
class AccountOperationsFullJourneyIntegrationTest {
    private static final UUID CORRELATION = id(1);
    private static final String PASSWORD = "correct horse battery staple 2026!";
    private static final String RESET_PASSWORD = "another correct horse staple 2027!";
    private static final String CODE_PASSWORD = "recovery code horse staple 2028!";
    private static final String OPERATOR_SUBJECT = "a22f-operator-subject";
    private static final String CATALOG = "a22f-rbac-v1";
    private static final UUID OPERATOR = id(10);
    private static final UUID OPERATOR_ROLE = id(11);
    private static final UUID QUEUE_READ = id(20);
    private static final UUID DETAIL_READ = id(21);
    private static final UUID ASSIGN = id(22);
    private static final UUID REASSIGN = id(23);
    private static final UUID UNASSIGN = id(24);
    private static final UUID START_REVIEW = id(25);
    private static final UUID REQUEST_INFORMATION = id(26);
    private static final UUID RESOLVE = id(27);
    private static final UUID REJECT = id(28);
    private static final UUID CASE_APPLY_SANCTION = id(29);
    private static final UUID CASE_RELEASE_SANCTION = id(30);
    private static final UUID SANCTION_APPLY = id(31);
    private static final UUID SANCTION_LIFT = id(32);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        String key = Base64.getEncoder().encodeToString(
                "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        registry.add("identity.crypto.email-encryption-key", () -> key);
        registry.add("identity.crypto.lookup-hmac-key", () -> key);
        registry.add("identity.crypto.verification-hmac-key", () -> key);
        registry.add("identity.crypto.session-hmac-key", () -> key);
        registry.add("idea2strategy.operator-auth.enabled", () -> "true");
        registry.add("idea2strategy.operator-auth.issuer", () -> "https://operator.example");
        registry.add("idea2strategy.operator-auth.jwk-set-uri", () -> "https://operator.example/jwks");
        registry.add("idea2strategy.operator-auth.audience", () -> "operator-api");
        registry.add("idea2strategy.operator-auth.current-subject-hmac-key-version", () -> "1");
        registry.add("idea2strategy.operator-auth.current-subject-hmac-key", () -> key);
        registry.add("idea2strategy.operator-auth.allowed-amr-values", () -> "mfa");
        registry.add("idea2strategy.operator-auth.maximum-mfa-age", () -> "PT10M");
        registry.add("idea2strategy.operator-case.guard.queue-permission-id", QUEUE_READ::toString);
        registry.add("idea2strategy.operator-case.guard.detail-permission-id", DETAIL_READ::toString);
        registry.add("idea2strategy.operator-case.guard.assign-permission-id", ASSIGN::toString);
        registry.add("idea2strategy.operator-case.guard.reassign-permission-id", REASSIGN::toString);
        registry.add("idea2strategy.operator-case.guard.unassign-permission-id", UNASSIGN::toString);
        registry.add("idea2strategy.operator-case.guard.start-review-permission-id", START_REVIEW::toString);
        registry.add("idea2strategy.operator-case.guard.request-information-permission-id",
                REQUEST_INFORMATION::toString);
        registry.add("idea2strategy.operator-case.guard.resolve-permission-id", RESOLVE::toString);
        registry.add("idea2strategy.operator-case.guard.reject-permission-id", REJECT::toString);
        registry.add("idea2strategy.operator-case.guard.apply-sanction-permission-id",
                CASE_APPLY_SANCTION::toString);
        registry.add("idea2strategy.operator-case.guard.release-sanction-permission-id",
                CASE_RELEASE_SANCTION::toString);
        registry.add("idea2strategy.operator-sanction.guard.apply-permission-id", SANCTION_APPLY::toString);
        registry.add("idea2strategy.operator-sanction.guard.lift-permission-id", SANCTION_LIFT::toString);
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;
    @Autowired AccountLifecycleService lifecycle;
    @Autowired NotificationPersistenceAdapter notifications;
    @Autowired VersionedOperatorSubjectHmac operatorSubjects;
    @Autowired @Qualifier("operatorJwtDecoder") JwtDecoder operatorJwtDecoder;

    @Test
    void walksRecoveryDormancyWithdrawalAndReactivationOverHttpAndPostgres() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String email = "a22f-lifecycle@example.com";
        UUID accountId = signupVerifyLogin(mvc, email).accountId();

        mvc.perform(post("/api/v1/auth/password-reset-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", UUID.randomUUID())
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isAccepted());
        String resetToken = events.stream(PasswordResetEmailRequested.class)
                .map(PasswordResetEmailRequested::resetToken)
                .findFirst().orElseThrow();
        mvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", UUID.randomUUID())
                        .content("""
                                {"resetToken":"%s","newPassword":"%s"}
                                """.formatted(resetToken, RESET_PASSWORD)))
                .andExpect(status().isNoContent());
        String resetSession = login(mvc, email, RESET_PASSWORD, accountId);

        String issued = mvc.perform(post("/api/v1/auth/recovery-codes")
                        .header("Authorization", "Bearer " + resetSession)
                        .header("X-Correlation-Id", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String recoveryCode = json.readTree(issued).get("recoveryCodes").get(0).asText();
        mvc.perform(post("/api/v1/auth/recovery-code-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", UUID.randomUUID())
                        .content("""
                                {"email":"%s","recoveryCode":"%s","newPassword":"%s"}
                                """.formatted(email, recoveryCode, CODE_PASSWORD)))
                .andExpect(status().isNoContent());
        login(mvc, email, CODE_PASSWORD, accountId);

        mvc.perform(post("/api/v1/account/withdrawal-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "a22f-withdrawal")
                        .header("X-Correlation-Id", UUID.randomUUID())
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, CODE_PASSWORD)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CLOSING"));
        mvc.perform(post("/api/v1/account/withdrawal-cancellations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "a22f-withdrawal-cancel")
                        .header("X-Correlation-Id", UUID.randomUUID())
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, CODE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        jdbc.update("update identity.accounts set last_successful_auth_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(400))), accountId);
        assertThat(lifecycle.markDormantCandidates(10))
                .anySatisfy(result -> assertThat(result.accountId()).isEqualTo(accountId));
        assertThat(text("select lifecycle_status::text from identity.accounts where id = ?", accountId))
                .isEqualTo("DORMANT");
        assertThat(count("select count(*) from identity.sessions where account_id = ? and revoked_at is null",
                accountId)).isZero();

        mvc.perform(post("/api/v1/account/reactivations/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "a22f-reactivation")
                        .header("X-Correlation-Id", UUID.randomUUID())
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, CODE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        login(mvc, email, CODE_PASSWORD, accountId);
    }

    @Test
    void sanctionAppealOperatorCaseAndNotificationWorkerFlowEndsOnTheUsersFeed() throws Exception {
        ensureOperatorRbac();
        when(operatorJwtDecoder.decode("operator-jwt")).thenReturn(operatorJwt());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String email = "a22f-sanctioned@example.com";
        Login user = signupVerifyLogin(mvc, email);
        UUID accountId = user.accountId();

        UUID sanctionId = id(60);
        String sanctionBody = """
                {"sanctionId":"%s","type":"SUSPENSION","reasonCode":"A22F_ABUSE",
                 "expiresAt":"%s","correlationId":"%s",
                 "idempotencyKey":"a22f-sanction-apply","requestHash":"%s","expectedVersion":0}
                """.formatted(sanctionId, Instant.now().plus(Duration.ofDays(7)), CORRELATION, "0".repeat(64));
        String applied = mvc.perform(post("/api/v1/operations/accounts/{accountId}/sanctions", accountId)
                        .header("Authorization", "Bearer operator-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sanctionBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long sanctionVersion = json.readTree(applied).get("aggregateVersion").asLong();
        mvc.perform(post("/api/v1/operations/accounts/{accountId}/sanctions", accountId)
                        .header("Authorization", "Bearer operator-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sanctionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregateVersion").value(sanctionVersion));
        assertThat(count("select count(*) from identity.account_sanctions where id = ?", sanctionId)).isOne();
        assertThat(count("select count(*) from identity.sessions where account_id = ? and revoked_at is null",
                accountId)).isZero();
        assertThat(count("select count(*) from operations.audit_events "
                + "where target_domain = 'ACCOUNT_SANCTION' and correlation_id = ?", CORRELATION))
                .isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/v1/cases/{caseId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isUnauthorized());

        TokenPair appealSession = loginTokens(mvc, email, PASSWORD, accountId);
        assertThat(count("""
                select count(*) from identity.authentication_events
                where account_id = ? and event_type = 'SANCTIONED_LOGIN_SUCCEEDED'
                  and reason_code = 'ACTIVE_ACCOUNT_SANCTION'
                """, accountId)).isOne();
        mvc.perform(get("/api/v1/auth/sessions")
                        .header("Authorization", "Bearer " + appealSession.refreshToken())
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SANCTION_ACTIVE"))
                .andExpect(jsonPath("$.appeal_available").value(true));
        mvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + appealSession)
                        .header("Idempotency-Key", "a22f-non-appeal")
                        .content("""
                                {"type":"INQUIRY","subject":"Unrelated access",
                                 "description":"This is outside the sanction allowlist.","evidence":[]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SANCTION_ACTIVE"));
        assertThat(count("""
                select count(*) from identity.authentication_events
                where account_id = ? and event_type = 'SESSION_REJECTED'
                  and reason_code = 'ACTIVE_ACCOUNT_SANCTION'
                """, accountId)).isGreaterThanOrEqualTo(2);
        String appealBody = """
                {"type":"APPEAL","subject":"Appeal the suspension",
                 "description":"The sanction was applied to the wrong account.","evidence":[]}
                """;
        String appeal = mvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + appealSession)
                        .header("X-Correlation-Id", CORRELATION)
                        .header("Idempotency-Key", "a22f-appeal")
                        .content(appealBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(json.readTree(appeal).get("id").asText());
        mvc.perform(get("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + appealSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("APPEAL"));

        String queue = mvc.perform(get("/api/v1/operations/cases")
                        .param("type", "APPEAL")
                        .header("Authorization", "Bearer operator-jwt")
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(queue).contains(caseId.toString());
        String detail = mvc.perform(get("/api/v1/operations/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer operator-jwt"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long caseVersion = json.readTree(detail).get("version").asLong();

        caseVersion = command(mvc, caseId, "ASSIGN", """
                {"expectedVersion":%d,"assigneeOperatorId":"%s","reasonCode":"A22F_ASSIGN",
                 "expectedSanctionVersion":0}
                """.formatted(caseVersion, OPERATOR), "a22f-case-assign");
        caseVersion = command(mvc, caseId, "START_REVIEW", """
                {"expectedVersion":%d,"reasonCode":"A22F_REVIEW","expectedSanctionVersion":0}
                """.formatted(caseVersion), "a22f-case-review");
        command(mvc, caseId, "RELEASE_SANCTION", """
                {"expectedVersion":%d,"reasonCode":"APPEAL_ACCEPTED","sanctionId":"%s",
                 "expectedSanctionVersion":%d}
                """.formatted(caseVersion, sanctionId, sanctionVersion), "a22f-case-release");

        assertThat(text("select status::text from identity.account_sanctions where id = ?", sanctionId))
                .isEqualTo("LIFTED");
        assertThat(text("select status::text from operations.cases where id = ?", caseId))
                .isEqualTo("RESOLVED");
        assertThat(count("select count(*) from operations.case_events where case_id = ?", caseId))
                .isGreaterThanOrEqualTo(4);

        var outbox = new TransactionalOutboxStore(jdbc);
        var staged = outbox.claimDueMatching("a22f-notification-worker", "a22f-policy-v1",
                Duration.ofSeconds(30), 1, "OPERATIONS_CASE", "CASE_SANCTION_RELEASED").getFirst();
        assertThat(staged.aggregateId()).isEqualTo(caseId);
        ensureNotificationPolicy("CASE_UPDATE");
        var consumer = new NotificationEventConsumer(outbox, notifications, Clock.systemUTC());
        var request = new NotificationRequest(accountId, "CASE_UPDATE", "template-v1", "ko",
                staged.messageId().toString(), staged.payloadHash(),
                Map.of("caseId", caseId.toString()), UUID.randomUUID());
        var receipt = consumer.consume(staged, request, "a22f-notification-worker", Duration.ofSeconds(30));
        var redelivered = consumer.consume(staged, request, "a22f-notification-worker", Duration.ofSeconds(30));
        assertThat(redelivered.notificationId()).isEqualTo(receipt.notificationId());

        var worker = new NotificationEmailWorker(jdbc, json, outbox,
                message -> EmailDeliveryGateway.DeliveryResult.sent("a22f-provider-key"));
        var delivery = outbox.claimDueMatching("a22f-email-worker", "a22f-policy-v1",
                Duration.ofSeconds(30), 1, "notification", "NOTIFICATION_EMAIL_DELIVERY").getFirst();
        assertThat(worker.deliver(delivery, "a22f-policy-v1", 3, Duration.ofSeconds(1)))
                .isEqualTo(NotificationEmailWorker.DeliveryDisposition.COMPLETED);

        mvc.perform(get("/api/v1/account/notifications")
                        .header("Authorization", "Bearer " + appealSession)
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].typeCode").value("CASE_UPDATE"));
        assertThat(text("""
                select status::text from operations.outbox_consumer_receipts
                where outbox_message_id = ?
                """, staged.messageId())).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from operations.delivery_attempts where notification_id = ?",
                receipt.notificationId())).isOne();
    }

    @Test
    void fakeStrategyBotAndRoomEventsCorrelateThroughOutboxWorkerAndCases() throws Exception {
        ensureOperatorRbac();
        when(operatorJwtDecoder.decode("operator-jwt")).thenReturn(operatorJwt());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String email = "a22f-provider@example.com";
        Login user = signupVerifyLogin(mvc, email);

        UUID botEventId = id(71);
        jdbc.update("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, event_type, event_schema_version,
                     payload_document, idempotency_key)
                values (?, 'bot', ?, 'BOT_UPDATE', '1', cast(? as jsonb), ?)
                """, botEventId, id(72),
                "{\"botId\":\"%s\",\"kind\":\"fake-strategy-bot.v1\"}".formatted(id(72)),
                "a22f-bot-event:" + botEventId);
        ensureNotificationPolicy("BOT_UPDATE");
        var outbox = new TransactionalOutboxStore(jdbc);
        var botEvent = outbox.claimDueMatching("a22f-provider-worker", "a22f-policy-v1",
                Duration.ofSeconds(30), 1, "bot", "BOT_UPDATE").getFirst();
        var consumer = new NotificationEventConsumer(outbox, notifications, Clock.systemUTC());
        var request = new NotificationRequest(user.accountId(), "BOT_UPDATE", "template-v1", "ko",
                botEvent.messageId().toString(), botEvent.payloadHash(),
                Map.of("botId", id(72).toString()), id(73));
        var receipt = consumer.consume(botEvent, request, "a22f-provider-worker", Duration.ofSeconds(30));
        assertThat(consumer.consume(botEvent, request, "a22f-provider-worker", Duration.ofSeconds(30))
                .notificationId()).isEqualTo(receipt.notificationId());
        var worker = new NotificationEmailWorker(jdbc, json, outbox,
                message -> EmailDeliveryGateway.DeliveryResult.sent("a22f-provider-key"));
        var delivery = outbox.claimDueMatching("a22f-provider-email", "a22f-policy-v1",
                Duration.ofSeconds(30), 1, "notification", "NOTIFICATION_EMAIL_DELIVERY").getFirst();
        assertThat(worker.deliver(delivery, "a22f-policy-v1", 3, Duration.ofSeconds(1)))
                .isEqualTo(NotificationEmailWorker.DeliveryDisposition.COMPLETED);
        String feed = mvc.perform(get("/api/v1/account/notifications")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(feed).contains("BOT_UPDATE");
        assertThat(text("select source_event_id from operations.notifications where id = ?",
                receipt.notificationId())).isEqualTo(botEvent.messageId().toString());

        String reportBody = """
                {"type":"REPORT","subject":"Fake room incident %s",
                 "description":"Versioned fake room summary for the operator review.","evidence":[]}
                """.formatted(id(74));
        String report = mvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + user.accessToken())
                        .header("X-Correlation-Id", CORRELATION)
                        .header("Idempotency-Key", "a22f-room-report")
                        .content(reportBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(json.readTree(report).get("id").asText());

        String detail = mvc.perform(get("/api/v1/operations/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer operator-jwt"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long caseVersion = json.readTree(detail).get("version").asLong();
        caseVersion = command(mvc, caseId, "ASSIGN", """
                {"expectedVersion":%d,"assigneeOperatorId":"%s","reasonCode":"A22F_ASSIGN",
                 "expectedSanctionVersion":0}
                """.formatted(caseVersion, OPERATOR), "a22f-room-assign");
        caseVersion = command(mvc, caseId, "START_REVIEW", """
                {"expectedVersion":%d,"reasonCode":"A22F_REVIEW","expectedSanctionVersion":0}
                """.formatted(caseVersion), "a22f-room-review");
        command(mvc, caseId, "REQUEST_INFORMATION", """
                {"expectedVersion":%d,"reasonCode":"A22F_MORE_INFO","expectedSanctionVersion":0}
                """.formatted(caseVersion), "a22f-room-more-info");

        assertThat(text("select status::text from operations.cases where id = ?", caseId))
                .isEqualTo("NEEDS_INFORMATION");
        assertThat(jdbc.queryForObject(
                "select response_deadline_at is not null from operations.cases where id = ?",
                Boolean.class, caseId)).isTrue();
        assertThat(count("select count(*) from operations.outbox_messages "
                + "where aggregate_id = ? and event_type = 'CASE_INFORMATION_REQUESTED'", caseId)).isOne();
        assertThat(count("select count(*) from operations.case_events "
                + "where case_id = ? and correlation_id = ?", caseId, CORRELATION))
                .isGreaterThanOrEqualTo(3);
    }

    private long command(MockMvc mvc, UUID caseId, String action, String body, String idempotencyKey)
            throws Exception {
        String response = mvc.perform(post("/api/v1/operations/cases/{caseId}/commands/{action}", caseId, action)
                        .header("Authorization", "Bearer operator-jwt")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/operations/cases/{caseId}/commands/{action}", caseId, action)
                        .header("Authorization", "Bearer operator-jwt")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Correlation-Id", CORRELATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        return json.readTree(response).get("caseVersion").asLong();
    }

    private Login signupVerifyLogin(MockMvc mvc, String email) throws Exception {
        String signup = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", CORRELATION)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID accountId = UUID.fromString(json.readTree(signup).get("accountId").asText());
        String verificationToken = events.stream(VerificationEmailRequested.class)
                .filter(event -> event.email().equals(email))
                .map(VerificationEmailRequested::verificationToken)
                .findFirst().orElseThrow();
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", CORRELATION)
                        .content("""
                                {"verificationToken":"%s"}
                                """.formatted(verificationToken)))
                .andExpect(status().isNoContent());
        return new Login(accountId, login(mvc, email, PASSWORD, accountId));
    }

    private String login(MockMvc mvc, String email, String password, UUID accountId) throws Exception {
        return loginTokens(mvc, email, password, accountId).accessToken();
    }

    private TokenPair loginTokens(MockMvc mvc, String email, String password, UUID accountId) throws Exception {
        String login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", UUID.randomUUID())
                        .content("""
                                {"email":"%s","password":"%s","deviceLabel":"a22f-journey"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andReturn().getResponse().getContentAsString();
        var response = json.readTree(login);
        return new TokenPair(response.get("accessToken").asText(), response.get("refreshToken").asText());
    }

    private void ensureNotificationPolicy(String typeCode) {
        jdbc.update("""
                insert into operations.notification_policies
                    (type_code, policy_version, mandatory, default_channels, active, activated_at)
                values (?, 'a22f-v1', true, cast('["APP","EMAIL"]' as jsonb), true, clock_timestamp())
                on conflict do nothing
                """, typeCode);
    }

    private void ensureOperatorRbac() {
        if (count("select count(*) from operations.operator_accounts where id = ?", OPERATOR) > 0) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        String subjectHash = operatorSubjects
                .protect("https://operator.example", OPERATOR_SUBJECT).getFirst().digest();
        jdbc.update("insert into operations.roles (id, code, hierarchy_rank, status) "
                + "values (?, 'A22F_OPERATIONS', 100, 'ACTIVE')", OPERATOR_ROLE);
        jdbc.update("insert into operations.operator_accounts "
                        + "(id, external_identity_key_hmac, external_identity_key_version, status, "
                        + "mfa_enrolled_at, last_mfa_verified_at, created_at) "
                        + "values (?, ?, 1, 'ACTIVE', ?, ?, ?)",
                OPERATOR, subjectHash, now, now, now);
        jdbc.update("insert into operations.rbac_catalog_versions "
                + "(catalog_version, content_hash, status, activated_at) values (?, ?, 'DRAFT', null)",
                CATALOG, "c".repeat(64));
        jdbc.update("insert into operations.rbac_catalog_roles "
                + "(catalog_version, role_id, hierarchy_rank, role_status) values (?, ?, 100, 'ACTIVE')",
                CATALOG, OPERATOR_ROLE);
        List<UUID> permissions = List.of(QUEUE_READ, DETAIL_READ, ASSIGN, REASSIGN, UNASSIGN,
                START_REVIEW, REQUEST_INFORMATION, RESOLVE, REJECT, CASE_APPLY_SANCTION,
                CASE_RELEASE_SANCTION, SANCTION_APPLY, SANCTION_LIFT);
        for (UUID permission : permissions) {
            jdbc.update("insert into operations.permissions (id, code, description, sensitivity) "
                            + "values (?, ?, 'a22f journey permission', 'HIGH')",
                    permission, "A22F_" + permission.toString().substring(24));
            jdbc.update("insert into operations.rbac_catalog_permissions "
                            + "(catalog_version, permission_id, permission_status) values (?, ?, 'ACTIVE')",
                    CATALOG, permission);
            jdbc.update("insert into operations.rbac_catalog_role_permissions "
                            + "(catalog_version, role_id, permission_id, delegable) values (?, ?, ?, true)",
                    CATALOG, OPERATOR_ROLE, permission);
        }
        jdbc.update("update operations.rbac_catalog_versions set status = 'ACTIVE', activated_at = ? "
                + "where catalog_version = ?", now, CATALOG);
        jdbc.update("insert into operations.operator_role_assignments "
                        + "(id, operator_account_id, role_id, catalog_version, granted_by_operator_id, granted_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                id(12), OPERATOR, OPERATOR_ROLE, CATALOG, OPERATOR, now);
    }

    private Jwt operatorJwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("operator-jwt")
                .header("alg", "RS256")
                .header("kid", "operator-key-1")
                .issuer("https://operator.example")
                .subject(OPERATOR_SUBJECT)
                .audience(List.of("operator-api"))
                .issuedAt(now.minusSeconds(30))
                .notBefore(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(300))
                .claim("amr", List.of("mfa"))
                .claim("auth_time", now.minusSeconds(30))
                .build();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a22f0000-0000-4000-8000-" + "%012d".formatted(suffix));
    }

    private record Login(UUID accountId, String accessToken) {}

    private record TokenPair(String accessToken, String refreshToken) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class OperatorJwtTestConfiguration {
        @Bean
        @Primary
        @Qualifier("operatorJwtDecoder")
        JwtDecoder testOperatorJwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }
}
