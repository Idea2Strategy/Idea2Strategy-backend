package com.idea2strategy.backend.application.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DelegatedAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("a1500000-0000-4000-8000-000000000001");
    private static final UUID AUTHORIZATION_ID = UUID.fromString("a1500000-0000-4000-8000-000000000002");
    private static final UUID REPLACEMENT_ID = UUID.fromString("a1500000-0000-4000-8000-000000000003");
    private static final UUID CREDENTIAL_ID = UUID.fromString("a1500000-0000-4000-8000-000000000004");
    private static final UUID POLICY_ID = UUID.fromString("a1500000-0000-4000-8000-000000000005");
    private static final UUID CORRELATION_ID = UUID.fromString("a1500000-0000-4000-8000-000000000006");
    private static final UUID TARGET_ID = UUID.fromString("a1500000-0000-4000-8000-000000000007");

    @Test
    void createsAnImmutableVersionAndReturnsRawCredentialOnlyOnFirstExecution() {
        var port = new InMemoryCommandPort();
        var service = service(port);
        var command = createCommand("create-1", "hash-1");

        DelegatedAuthorizationResult first = service.execute(command);
        DelegatedAuthorizationResult replay = service.execute(command);

        assertThat(first.authorizationId()).isEqualTo(AUTHORIZATION_ID);
        assertThat(first.authorizationVersion()).isEqualTo(1);
        assertThat(first.rawCredential()).contains("raw-once");
        assertThat(replay.rawCredential()).isEmpty();
        assertThat(port.decisionCalls).isEqualTo(1);
        assertThat(port.lastMutation.credentialDigest()).isEqualTo("digest-only");
        assertThat(port.lastMutation.toString()).doesNotContain("raw-once");
    }

    @Test
    void replacesByCreatingTheNextVersionAndRevokingThePredecessor() {
        var port = new InMemoryCommandPort();
        port.current = Optional.of(active(AUTHORIZATION_ID, 3));
        var service = service(port);
        var command = new DelegatedAuthorizationCommand(
                DelegatedAuthorizationCommandType.REPLACE,
                ACCOUNT_ID,
                REPLACEMENT_ID,
                AUTHORIZATION_ID,
                3,
                19,
                "replacement",
                POLICY_ID,
                Set.of(DelegatedAuthorizationScope.STRATEGY_EDIT),
                Set.of(TARGET_ID),
                NOW.plusSeconds(7200),
                null,
                "replace-1",
                "hash-2",
                CORRELATION_ID);

        DelegatedAuthorizationResult result = service.execute(command);

        assertThat(result.authorizationId()).isEqualTo(REPLACEMENT_ID);
        assertThat(result.authorizationVersion()).isEqualTo(4);
        assertThat(port.lastMutation.replacesAuthorizationId()).isEqualTo(AUTHORIZATION_ID);
        assertThat(port.lastMutation.predecessorRevokedAt()).isEqualTo(NOW);
        assertThat(port.lastMutation.scopes()).containsExactly(DelegatedAuthorizationScope.STRATEGY_EDIT);
    }

    @Test
    void revokesWithoutIssuingCredentialAndRejectsStaleVersions() {
        var port = new InMemoryCommandPort();
        port.current = Optional.of(active(AUTHORIZATION_ID, 2));
        var service = service(port);
        var stale = revokeCommand(1, "revoke-stale", "hash-3");

        assertThatThrownBy(() -> service.execute(stale))
                .isInstanceOf(DelegatedAuthorizationConflictException.class);

        DelegatedAuthorizationResult revoked = service.execute(revokeCommand(2, "revoke-1", "hash-4"));
        assertThat(revoked.status()).isEqualTo(DelegatedAuthorizationStatus.REVOKED);
        assertThat(revoked.rawCredential()).isEmpty();
        assertThat(port.lastMutation.credentialDigest()).isNull();
    }

    @Test
    void rejectsAnIdempotencyKeyReusedWithDifferentContent() {
        var port = new InMemoryCommandPort();
        var service = service(port);
        service.execute(createCommand("same-key", "hash-a"));

        assertThatThrownBy(() -> service.execute(createCommand("same-key", "hash-b")))
                .isInstanceOf(DelegatedAuthorizationIdempotencyException.class);
    }

    private static DelegatedAuthorizationService service(InMemoryCommandPort port) {
        return new DelegatedAuthorizationService(
                port,
                () -> new DelegatedCredentialMaterial("raw-once", "digest-only", (short) 7),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> CREDENTIAL_ID);
    }

    private static DelegatedAuthorizationCommand createCommand(String key, String hash) {
        return new DelegatedAuthorizationCommand(
                DelegatedAuthorizationCommandType.CREATE,
                ACCOUNT_ID,
                AUTHORIZATION_ID,
                null,
                0,
                19,
                "client",
                POLICY_ID,
                Set.of(DelegatedAuthorizationScope.STRATEGY_CREATE, DelegatedAuthorizationScope.STRATEGY_EDIT),
                Set.of(TARGET_ID),
                NOW.plusSeconds(3600),
                null,
                key,
                hash,
                CORRELATION_ID);
    }

    private static DelegatedAuthorizationCommand revokeCommand(long version, String key, String hash) {
        return new DelegatedAuthorizationCommand(
                DelegatedAuthorizationCommandType.REVOKE,
                ACCOUNT_ID,
                AUTHORIZATION_ID,
                null,
                version,
                19,
                "client",
                POLICY_ID,
                Set.of(),
                Set.of(),
                null,
                "USER_REVOKED",
                key,
                hash,
                CORRELATION_ID);
    }

    private static DelegatedAuthorizationSnapshot active(UUID id, long version) {
        return new DelegatedAuthorizationSnapshot(
                id, ACCOUNT_ID, version, DelegatedAuthorizationStatus.ACTIVE, 19, null, null);
    }

    private static final class InMemoryCommandPort implements DelegatedAuthorizationCommandPort {
        private final Map<String, Receipt> receipts = new HashMap<>();
        private Optional<DelegatedAuthorizationSnapshot> current = Optional.empty();
        private DelegatedAuthorizationMutation lastMutation;
        private int decisionCalls;

        @Override
        public DelegatedAuthorizationExecution executeAtomically(
                DelegatedAuthorizationCommand command,
                Instant at,
                DelegatedAuthorizationDecision decision) {
            Receipt receipt = receipts.get(command.idempotencyKey());
            if (receipt != null) {
                if (!receipt.requestHash.equals(command.requestHash())) {
                    throw new DelegatedAuthorizationIdempotencyException();
                }
                return new DelegatedAuthorizationExecution(receipt.result, false);
            }
            decisionCalls++;
            lastMutation = decision.decide(current);
            DelegatedAuthorizationResult result = lastMutation.toStoredResult();
            receipts.put(command.idempotencyKey(), new Receipt(command.requestHash(), result));
            return new DelegatedAuthorizationExecution(result, true);
        }
    }

    private record Receipt(String requestHash, DelegatedAuthorizationResult result) {}
}
