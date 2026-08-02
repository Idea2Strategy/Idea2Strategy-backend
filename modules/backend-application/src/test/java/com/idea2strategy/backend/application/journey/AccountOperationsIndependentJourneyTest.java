package com.idea2strategy.backend.application.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.AccountOperationsJourneyFixture;
import com.idea2strategy.backend.application.testing.AccountOperationsJourneyFixture.FakeDomain;
import com.idea2strategy.backend.application.testing.AccountOperationsJourneyFixture.JourneyRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountOperationsIndependentJourneyTest {
    private static final Instant NOW = Instant.parse("2026-08-02T18:00:00Z");
    private static final UUID OPERATOR_PERMISSION = uuid(1);
    private static final UUID USER_CORRELATION = uuid(11);
    private static final UUID OPERATIONS_CORRELATION = uuid(12);
    private static final UUID DELIVERY_CORRELATION = uuid(13);

    @Test
    void runsTheIndependentAccountAndOperationsJourneyWithVersionedFakeIncidents() {
        var fixture = fixture();

        assertThat(fixture.registerAndVerify("signup-1", hash('a'), USER_CORRELATION))
                .isEqualTo("ACCOUNT_ACTIVE");
        assertThat(fixture.login("login-1", hash('b'), USER_CORRELATION))
                .isEqualTo("SESSION_ACTIVE");
        assertThat(fixture.updatePreferences(
                        "ko", "America/New_York", "SYSTEM", "preferences-1", hash('c'), USER_CORRELATION))
                .isEqualTo("PREFERENCES_UPDATED");

        fixture.activateOperatorMfa();
        fixture.recordFakeIncident(
                FakeDomain.STRATEGY,
                AccountOperationsJourneyFixture.STRATEGY_INCIDENT_ID,
                1,
                "STRATEGY_REFERENCE_AVAILABLE",
                OPERATIONS_CORRELATION);
        fixture.recordFakeIncident(
                FakeDomain.BOT,
                AccountOperationsJourneyFixture.BOT_INCIDENT_ID,
                2,
                "BOT_EXECUTION_BLOCKED",
                OPERATIONS_CORRELATION);
        fixture.recordFakeIncident(
                FakeDomain.ROOM,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                3,
                "ROOM_RESULT_REVIEW_REQUIRED",
                OPERATIONS_CORRELATION);
        assertThat(fixture.invokeOperatorTool(
                        OPERATOR_PERMISSION,
                        FakeDomain.ROOM,
                        AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                        3,
                        OPERATIONS_CORRELATION))
                .isEqualTo("ROOM_RESULT_REVIEW_REQUIRED");

        assertThat(fixture.submitCase(
                        AccountOperationsJourneyFixture.ACCOUNT_ID,
                        AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                        "case-submit-1",
                        hash('d'),
                        USER_CORRELATION))
                .isEqualTo("CASE_OPEN");
        assertThat(fixture.assignAndStartReview(
                        OPERATOR_PERMISSION, "case-review-1", hash('e'), OPERATIONS_CORRELATION))
                .isEqualTo("CASE_UNDER_REVIEW");
        assertThat(fixture.applySanctionAndResolveCase(
                        OPERATOR_PERMISSION, "sanction-1", hash('f'), OPERATIONS_CORRELATION))
                .isEqualTo("CASE_RESOLVED_WITH_SANCTION");

        var afterResolution = fixture.snapshot();
        assertThat(afterResolution.sanctioned()).isTrue();
        assertThat(afterResolution.sessionActive()).isFalse();
        assertThat(afterResolution.caseStatus())
                .isEqualTo(AccountOperationsJourneyFixture.CaseStatus.RESOLVED);
        assertThat(afterResolution.traces())
                .anyMatch(trace -> trace.correlationId().equals(OPERATIONS_CORRELATION)
                        && trace.stage().equals("MCP"))
                .anyMatch(trace -> trace.correlationId().equals(OPERATIONS_CORRELATION)
                        && trace.stage().equals("OUTBOX"))
                .anyMatch(trace -> trace.correlationId().equals(OPERATIONS_CORRELATION)
                        && trace.stage().equals("AUDIT"));

        fixture.liftSanction(OPERATIONS_CORRELATION);
        assertThat(fixture.login("login-after-lift", hash('6'), USER_CORRELATION))
                .isEqualTo("SESSION_ACTIVE");
        assertThat(fixture.closeAccount("close-1", hash('1'), USER_CORRELATION))
                .isEqualTo("ACCOUNT_CLOSED");
        assertThat(fixture.snapshot().accountStatus())
                .isEqualTo(AccountOperationsJourneyFixture.AccountStatus.CLOSED);
    }

    @Test
    void preservesBusinessStateAcrossNotificationFailureAndRetriesOneOutboxMessage() {
        var fixture = resolvedCaseFixture();
        int messageCount = fixture.snapshot().outboxMessageCount();

        assertThat(fixture.deliverNextNotification(false, DELIVERY_CORRELATION).code())
                .isEqualTo("RETRY_SCHEDULED");
        assertThat(fixture.snapshot().caseStatus())
                .isEqualTo(AccountOperationsJourneyFixture.CaseStatus.RESOLVED);
        assertThat(fixture.snapshot().outboxMessageCount()).isEqualTo(messageCount);

        assertThat(fixture.deliverNextNotification(true, DELIVERY_CORRELATION).code())
                .isEqualTo("DELIVERED");
        assertThat(fixture.snapshot().deliveredNotificationCount()).isEqualTo(1);
        assertThat(fixture.snapshot().traces())
                .anyMatch(trace -> trace.correlationId().equals(DELIVERY_CORRELATION)
                        && trace.action().equals("NOTIFICATION_RETRY_SCHEDULED"))
                .anyMatch(trace -> trace.correlationId().equals(DELIVERY_CORRELATION)
                        && trace.action().equals("NOTIFICATION_DELIVERED"));
    }

    @Test
    void keepsUnauthorizedAndMissingResourcesNonEnumeratingAndCommandsIdempotent() {
        var fixture = fixture();
        fixture.registerAndVerify("signup", hash('2'), USER_CORRELATION);
        fixture.login("login", hash('3'), USER_CORRELATION);
        fixture.recordFakeIncident(
                FakeDomain.ROOM,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                1,
                "ROOM_REVIEW",
                OPERATIONS_CORRELATION);

        assertThatThrownBy(() -> fixture.caseDetail(uuid(99), AccountOperationsJourneyFixture.CASE_ID))
                .isInstanceOf(JourneyRejectedException.class)
                .hasMessage("RESOURCE_NOT_AVAILABLE");
        assertThatThrownBy(() -> fixture.invokeOperatorTool(
                        uuid(98), FakeDomain.ROOM, uuid(97), 1, OPERATIONS_CORRELATION))
                .isInstanceOf(JourneyRejectedException.class)
                .hasMessage("RESOURCE_NOT_AVAILABLE");

        String first = fixture.submitCase(
                AccountOperationsJourneyFixture.ACCOUNT_ID,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                "same-key",
                hash('4'),
                USER_CORRELATION);
        String replay = fixture.submitCase(
                AccountOperationsJourneyFixture.ACCOUNT_ID,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                "same-key",
                hash('4'),
                USER_CORRELATION);
        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> fixture.submitCase(
                        AccountOperationsJourneyFixture.ACCOUNT_ID,
                        AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                        "same-key",
                        hash('5'),
                        USER_CORRELATION))
                .isInstanceOf(JourneyRejectedException.class)
                .hasMessage("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void exposesA15AndA21AsExplicitFollowUpGatesInsteadOfInventingBehavior() {
        var fixture = fixture();

        assertThat(fixture.delegatedCredentialGate()).isEqualTo("A15_NOT_IN_STACK");
        assertThat(fixture.deadlineBatchGate()).isEqualTo("A21_NOT_IN_STACK");
    }

    private static AccountOperationsJourneyFixture resolvedCaseFixture() {
        var fixture = fixture();
        fixture.registerAndVerify("signup", hash('6'), USER_CORRELATION);
        fixture.login("login", hash('7'), USER_CORRELATION);
        fixture.activateOperatorMfa();
        fixture.recordFakeIncident(
                FakeDomain.ROOM,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                1,
                "ROOM_REVIEW",
                OPERATIONS_CORRELATION);
        fixture.submitCase(
                AccountOperationsJourneyFixture.ACCOUNT_ID,
                AccountOperationsJourneyFixture.ROOM_INCIDENT_ID,
                "submit",
                hash('8'),
                USER_CORRELATION);
        fixture.assignAndStartReview(OPERATOR_PERMISSION, "review", hash('9'), OPERATIONS_CORRELATION);
        fixture.applySanctionAndResolveCase(OPERATOR_PERMISSION, "sanction", hash('0'), OPERATIONS_CORRELATION);
        return fixture;
    }

    private static AccountOperationsJourneyFixture fixture() {
        return new AccountOperationsJourneyFixture(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Set.of(OPERATOR_PERMISSION),
                () -> "A15_NOT_IN_STACK",
                () -> "A21_NOT_IN_STACK");
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("50000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
