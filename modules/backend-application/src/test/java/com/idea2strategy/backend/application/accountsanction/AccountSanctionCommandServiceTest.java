package com.idea2strategy.backend.application.accountsanction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class AccountSanctionCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID SANCTION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_SANCTION = UUID.fromString("30000000-0000-4000-8000-000000000004");
    private static final UUID APPLY_PERMISSION = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID LIFT_PERMISSION = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final UUID CORRELATION = UUID.fromString("60000000-0000-4000-8000-000000000006");
    private static final OperatorRequestContext OPERATOR = new OperatorRequestContext(ACTOR, true, true);

    @Test
    void appliesATemporarySanctionAndPlansAccessRevocationAndDurableStopMessages() {
        var port = new RecordingPort(AccountSanctionState.empty(ACCOUNT));
        var authorization = new RecordingAuthorization(allowed(APPLY_PERMISSION));
        var access = new RecordingAccessRevocation();
        var outbox = new RecordingOutbox();

        AccountSanctionResult result = service(port, authorization, access, outbox).execute(apply(0));

        assertThat(result.status()).isEqualTo(AccountSanctionResult.Status.APPLIED);
        assertThat(result.code()).isEqualTo("SANCTION_APPLIED");
        assertThat(result.mutation().afterStatus()).isEqualTo(AccountSanctionState.Status.ACTIVE);
        assertThat(result.mutation().newVersion()).isEqualTo(1);
        assertThat(result.mutation().expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(authorization.requiredPermissions).containsExactly(APPLY_PERMISSION);
        assertThat(access.effects).singleElement().satisfies(effect -> {
            assertThat(effect.bumpAuthEpoch()).isTrue();
            assertThat(effect.revokeAllCredentials()).isTrue();
            assertThat(effect.reasonCode()).isEqualTo("ACCOUNT_SANCTION_APPLIED");
        });
        assertThat(outbox.messages).extracting(AccountSanctionOutboxPublicationPort.Message::type)
                .containsExactly(
                        "ACCOUNT_SANCTION_APPLIED",
                        "ACCOUNT_ACCESS_BLOCKED",
                        "ACCOUNT_EXECUTION_STOP_REQUESTED");
        assertThat(outbox.messages).allSatisfy(message -> {
            assertThat(message.accountId()).isEqualTo(ACCOUNT);
            assertThat(message.sanctionId()).isEqualTo(SANCTION);
            assertThat(message.correlationId()).isEqualTo(CORRELATION);
            assertThat(message.deduplicationKey()).endsWith(":1");
        });
        assertThat(port.effectsPublishedInsideAtomicBoundary).isTrue();
    }

    @Test
    void rejectsTrustedMfaOrPermissionDenialAsAnAuditedDecisionWithoutEffects() {
        var denied = new AccountSanctionAuthorizationPort.Decision(
                false, "OPERATOR_MFA_REQUIRED", "catalog-v1", Set.of(), Set.of(), true, false);
        var port = new RecordingPort(AccountSanctionState.empty(ACCOUNT));
        var access = new RecordingAccessRevocation();
        var outbox = new RecordingOutbox();

        AccountSanctionResult result = service(port, new RecordingAuthorization(denied), access, outbox)
                .execute(apply(0));

        assertThat(result.status()).isEqualTo(AccountSanctionResult.Status.REJECTED);
        assertThat(result.code()).isEqualTo("OPERATOR_MFA_REQUIRED");
        assertThat(result.authorization()).isEqualTo(denied);
        assertThat(port.committedResults).containsExactly(result);
        assertThat(access.effects).isEmpty();
        assertThat(outbox.messages).isEmpty();
    }

    @Test
    void rejectsAnUntrustedSubjectBeforeTheApplicationPortsAreUsed() {
        var command = copyWithContext(apply(0), new OperatorRequestContext(ACTOR, false, true));
        var port = new RecordingPort(AccountSanctionState.empty(ACCOUNT));
        var authorization = new RecordingAuthorization(allowed(APPLY_PERMISSION));

        assertThatThrownBy(() -> service(
                        port, authorization, new RecordingAccessRevocation(), new RecordingOutbox()).execute(command))
                .isInstanceOf(AccountSanctionAuthenticationRejectedException.class);
        assertThat(authorization.requiredPermissions).isEmpty();
        assertThat(port.committedResults).isEmpty();
    }

    @Test
    void failsClosedWhenAnAllowedGuardDecisionDoesNotProveMfaAndPermission() {
        var inconsistent = new AccountSanctionAuthorizationPort.Decision(
                true, "AUTHORIZED", "catalog-v1", Set.of(), Set.of(), true, false);

        AccountSanctionResult result = service(
                        new RecordingPort(AccountSanctionState.empty(ACCOUNT)),
                        new RecordingAuthorization(inconsistent),
                        new RecordingAccessRevocation(),
                        new RecordingOutbox())
                .execute(apply(0));

        assertThat(result.status()).isEqualTo(AccountSanctionResult.Status.REJECTED);
        assertThat(result.code()).isEqualTo("SANCTION_AUTHORIZATION_EVIDENCE_INVALID");
    }

    @Test
    void liftsTheLastActiveSanctionWithoutIssuingANewSession() {
        var active = active(SANCTION, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        var port = new RecordingPort(new AccountSanctionState(ACCOUNT, 7, List.of(active)));
        var access = new RecordingAccessRevocation();
        var outbox = new RecordingOutbox();

        AccountSanctionResult result = service(port, new RecordingAuthorization(allowed(LIFT_PERMISSION)), access, outbox)
                .execute(lift(7));

        assertThat(result.status()).isEqualTo(AccountSanctionResult.Status.APPLIED);
        assertThat(result.mutation().afterStatus()).isEqualTo(AccountSanctionState.Status.LIFTED);
        assertThat(result.mutation().sanctionReasonCode()).isEqualTo("RISK_REVIEW");
        assertThat(result.mutation().eventReasonCode()).isEqualTo("APPEAL_ACCEPTED");
        assertThat(access.effects).isEmpty();
        assertThat(outbox.messages).extracting(AccountSanctionOutboxPublicationPort.Message::type)
                .containsExactly("ACCOUNT_SANCTION_LIFTED", "ACCOUNT_ACCESS_RESTORED");
    }

    @Test
    void liftingOneSanctionDoesNotRestoreAccessWhileAnotherRemainsActive() {
        var state = new AccountSanctionState(
                ACCOUNT,
                2,
                List.of(
                        active(SANCTION, NOW.minusSeconds(60), NOW.plusSeconds(3600)),
                        new AccountSanctionState.Sanction(
                                OTHER_SANCTION,
                                AccountSanctionState.Type.PERMANENT,
                                AccountSanctionState.Status.ACTIVE,
                                "OTHER",
                                NOW.minusSeconds(30),
                                NOW.minusSeconds(30),
                                null,
                                null)));
        var outbox = new RecordingOutbox();

        AccountSanctionResult result = service(
                        new RecordingPort(state),
                        new RecordingAuthorization(allowed(LIFT_PERMISSION)),
                        new RecordingAccessRevocation(),
                        outbox)
                .execute(lift(2));

        assertThat(result.status()).isEqualTo(AccountSanctionResult.Status.APPLIED);
        assertThat(outbox.messages).extracting(AccountSanctionOutboxPublicationPort.Message::type)
                .containsExactly("ACCOUNT_SANCTION_LIFTED");
    }

    @Test
    void expiresExactlyAtTheTemporaryBoundaryWithoutOperatorAuthorization() {
        var active = active(SANCTION, NOW.minusSeconds(3600), NOW);
        var authorization = new RecordingAuthorization(allowed(APPLY_PERMISSION));
        var outbox = new RecordingOutbox();

        AccountSanctionResult result = service(
                        new RecordingPort(new AccountSanctionState(ACCOUNT, 4, List.of(active))),
                        authorization,
                        new RecordingAccessRevocation(),
                        outbox)
                .execute(expire(4));

        assertThat(result.status()).isEqualTo(AccountSanctionResult.Status.APPLIED);
        assertThat(result.mutation().afterStatus()).isEqualTo(AccountSanctionState.Status.EXPIRED);
        assertThat(result.mutation().actorOperatorId()).isNull();
        assertThat(authorization.requiredPermissions).isEmpty();
        assertThat(outbox.messages).extracting(AccountSanctionOutboxPublicationPort.Message::type)
                .containsExactly("ACCOUNT_SANCTION_EXPIRED", "ACCOUNT_ACCESS_RESTORED");
    }

    @Test
    void requiresExpiryInsteadOfAllowingALateManualLiftAtTheDeadline() {
        var active = active(SANCTION, NOW.minusSeconds(3600), NOW);

        AccountSanctionResult result = service(
                        new RecordingPort(new AccountSanctionState(ACCOUNT, 4, List.of(active))),
                        new RecordingAuthorization(allowed(LIFT_PERMISSION)),
                        new RecordingAccessRevocation(),
                        new RecordingOutbox())
                .execute(lift(4));

        assertThat(result.status()).isEqualTo(AccountSanctionResult.Status.REJECTED);
        assertThat(result.code()).isEqualTo("SANCTION_EXPIRY_REQUIRED");
    }

    @Test
    void rejectsEarlyExpiryAndAStaleExpectedVersionWithoutSideEffects() {
        var active = active(SANCTION, NOW.minusSeconds(60), NOW.plusSeconds(1));
        var outbox = new RecordingOutbox();
        var service = service(
                new RecordingPort(new AccountSanctionState(ACCOUNT, 5, List.of(active))),
                new RecordingAuthorization(allowed(APPLY_PERMISSION)),
                new RecordingAccessRevocation(),
                outbox);

        assertThat(service.execute(expire(5)).code()).isEqualTo("SANCTION_NOT_EXPIRED");
        assertThat(service.execute(copyWithIdentity(expire(4), SANCTION, "stale-expiry")).code())
                .isEqualTo("SANCTION_VERSION_CONFLICT");
        assertThat(outbox.messages).isEmpty();
    }

    @Test
    void replaysTheSameIdempotencyKeyAndConflictsOnAnotherRequestHash() {
        var port = new RecordingPort(AccountSanctionState.empty(ACCOUNT));
        var service = service(
                port,
                new RecordingAuthorization(allowed(APPLY_PERMISSION)),
                new RecordingAccessRevocation(),
                new RecordingOutbox());
        AccountSanctionCommand command = apply(0);

        AccountSanctionResult first = service.execute(command);
        AccountSanctionResult replay = service.execute(command);

        assertThat(replay).isSameAs(first);
        assertThatThrownBy(() -> service.execute(copyWithRequestHash(command, hash('f'))))
                .isInstanceOf(AccountSanctionIdempotencyConflictException.class);
    }

    @Test
    void serializesCompetingCommandsSoOnlyOneExpectedVersionCanApply() throws Exception {
        var port = new RecordingPort(AccountSanctionState.empty(ACCOUNT));
        var service = service(
                port,
                new RecordingAuthorization(allowed(APPLY_PERMISSION)),
                new RecordingAccessRevocation(),
                new RecordingOutbox());
        Callable<AccountSanctionResult> first = () -> service.execute(apply(0));
        Callable<AccountSanctionResult> second = () -> service.execute(copyWithIdentity(apply(0), OTHER_SANCTION, "other"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(first, second)).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertThat(results).extracting(AccountSanctionResult::status)
                    .containsExactlyInAnyOrder(AccountSanctionResult.Status.APPLIED, AccountSanctionResult.Status.REJECTED);
            assertThat(results).extracting(AccountSanctionResult::code)
                    .containsExactlyInAnyOrder("SANCTION_APPLIED", "SANCTION_VERSION_CONFLICT");
        }
    }

    @Test
    void enforcesTemporaryAndPermanentCommandShape() {
        assertThatThrownBy(() -> new AccountSanctionCommand(
                        AccountSanctionCommand.Type.APPLY,
                        OPERATOR,
                        ACCOUNT,
                        SANCTION,
                        AccountSanctionState.Type.SUSPENSION,
                        "RISK",
                        null,
                        null,
                        CORRELATION,
                        "missing-expiry",
                        hash('a'),
                        0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountSanctionCommand(
                        AccountSanctionCommand.Type.APPLY,
                        OPERATOR,
                        ACCOUNT,
                        SANCTION,
                        AccountSanctionState.Type.PERMANENT,
                        "RISK",
                        NOW.plusSeconds(1),
                        null,
                        CORRELATION,
                        "permanent-expiry",
                        hash('b'),
                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AccountSanctionCommandService service(
            RecordingPort port,
            RecordingAuthorization authorization,
            RecordingAccessRevocation access,
            RecordingOutbox outbox) {
        return new AccountSanctionCommandService(
                port, authorization, access, outbox, APPLY_PERMISSION, LIFT_PERMISSION,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AccountSanctionCommand apply(long expectedVersion) {
        return new AccountSanctionCommand(
                AccountSanctionCommand.Type.APPLY,
                OPERATOR,
                ACCOUNT,
                SANCTION,
                AccountSanctionState.Type.SUSPENSION,
                "RISK_REVIEW",
                NOW.plusSeconds(3600),
                null,
                CORRELATION,
                "apply-key",
                hash('a'),
                expectedVersion);
    }

    private static AccountSanctionCommand lift(long expectedVersion) {
        return new AccountSanctionCommand(
                AccountSanctionCommand.Type.LIFT,
                OPERATOR,
                ACCOUNT,
                SANCTION,
                null,
                "APPEAL_ACCEPTED",
                null,
                null,
                CORRELATION,
                "lift-key",
                hash('b'),
                expectedVersion);
    }

    private static AccountSanctionCommand expire(long expectedVersion) {
        return new AccountSanctionCommand(
                AccountSanctionCommand.Type.EXPIRE,
                null,
                ACCOUNT,
                SANCTION,
                null,
                "TEMPORARY_SANCTION_EXPIRED",
                null,
                null,
                CORRELATION,
                "expire-key",
                hash('c'),
                expectedVersion);
    }

    private static AccountSanctionState.Sanction active(UUID sanctionId, Instant effectiveAt, Instant expiresAt) {
        return new AccountSanctionState.Sanction(
                sanctionId,
                AccountSanctionState.Type.SUSPENSION,
                AccountSanctionState.Status.ACTIVE,
                "RISK_REVIEW",
                effectiveAt,
                effectiveAt,
                expiresAt,
                null);
    }

    private static AccountSanctionAuthorizationPort.Decision allowed(UUID permission) {
        return new AccountSanctionAuthorizationPort.Decision(
                true, "AUTHORIZED", "catalog-v1", Set.of(UUID.randomUUID()), Set.of(permission), true, true);
    }

    private static AccountSanctionCommand copyWithContext(
            AccountSanctionCommand command, OperatorRequestContext context) {
        return new AccountSanctionCommand(
                command.type(), context, command.accountId(), command.sanctionId(), command.sanctionType(),
                command.reasonCode(), command.expiresAt(), command.sourceCaseId(), command.correlationId(),
                command.idempotencyKey(), command.requestHash(), command.expectedVersion());
    }

    private static AccountSanctionCommand copyWithRequestHash(
            AccountSanctionCommand command, String requestHash) {
        return new AccountSanctionCommand(
                command.type(), command.requestContext(), command.accountId(), command.sanctionId(), command.sanctionType(),
                command.reasonCode(), command.expiresAt(), command.sourceCaseId(), command.correlationId(),
                command.idempotencyKey(), requestHash, command.expectedVersion());
    }

    private static AccountSanctionCommand copyWithIdentity(
            AccountSanctionCommand command, UUID sanctionId, String key) {
        return new AccountSanctionCommand(
                command.type(), command.requestContext(), command.accountId(), sanctionId, command.sanctionType(),
                command.reasonCode(), command.expiresAt(), command.sourceCaseId(), UUID.randomUUID(), key,
                hash('d'), command.expectedVersion());
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static final class RecordingAuthorization implements AccountSanctionAuthorizationPort {
        private final Decision decision;
        private final List<UUID> requiredPermissions = new ArrayList<>();

        private RecordingAuthorization(Decision decision) {
            this.decision = decision;
        }

        @Override
        public Decision authorize(OperatorRequestContext context, UUID requiredPermissionId, Instant evaluatedAt) {
            requiredPermissions.add(requiredPermissionId);
            return decision;
        }
    }

    private static final class RecordingAccessRevocation implements AccountAccessRevocationPort {
        private final List<Effect> effects = new ArrayList<>();

        @Override
        public void revoke(Effect effect) {
            effects.add(effect);
        }
    }

    private static final class RecordingOutbox implements AccountSanctionOutboxPublicationPort {
        private final List<Message> messages = new ArrayList<>();

        @Override
        public void publish(List<Message> messages) {
            this.messages.addAll(messages);
        }
    }

    private static final class RecordingPort implements AccountSanctionCommandPort {
        private AccountSanctionState state;
        private final Map<String, Receipt> receipts = new HashMap<>();
        private final List<AccountSanctionResult> committedResults = new ArrayList<>();
        private boolean effectsPublishedInsideAtomicBoundary;

        private RecordingPort(AccountSanctionState state) {
            this.state = state;
        }

        @Override
        public synchronized AccountSanctionResult executeAtomically(
                AccountSanctionCommand command,
                Instant evaluatedAt,
                AccountSanctionAuthorizationPort.Decision authorization,
                AccountSanctionDecision decision,
                TransactionalEffects effects) {
            Receipt receipt = receipts.get(command.idempotencyKey());
            if (receipt != null) {
                if (!receipt.requestHash.equals(command.requestHash())) {
                    throw new AccountSanctionIdempotencyConflictException();
                }
                return receipt.result;
            }
            AccountSanctionResult result = decision.decide(state, authorization);
            if (result.status() == AccountSanctionResult.Status.APPLIED) {
                effects.publish(result);
                effectsPublishedInsideAtomicBoundary = true;
                var updated = new ArrayList<>(state.sanctions());
                updated.removeIf(sanction -> sanction.id().equals(result.mutation().sanctionId()));
                updated.add(result.mutation().toSanction());
                state = new AccountSanctionState(state.accountId(), result.mutation().newVersion(), updated);
            }
            receipts.put(command.idempotencyKey(), new Receipt(command.requestHash(), result));
            committedResults.add(result);
            return result;
        }
    }

    private record Receipt(String requestHash, AccountSanctionResult result) {}
}
