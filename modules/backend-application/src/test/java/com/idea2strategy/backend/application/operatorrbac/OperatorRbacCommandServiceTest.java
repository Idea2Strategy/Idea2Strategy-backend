package com.idea2strategy.backend.application.operatorrbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OperatorRbacCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T15:00:00Z");
    private static final UUID ACTOR_ID = uuid(1);
    private static final UUID TARGET_ID = uuid(2);
    private static final UUID HIGH_ROLE = uuid(11);
    private static final UUID LOW_ROLE = uuid(12);
    private static final UUID COMMAND_PERMISSION = uuid(21);
    private static final UUID LOW_PERMISSION = uuid(22);
    private static final UUID OTHER_PERMISSION = uuid(23);
    private static final UUID ASSIGNMENT_ID = uuid(31);

    @Test
    void grantsOnlyALowerRoleWhosePermissionsAreOwnedAndDelegable() {
        var port = new RecordingPort(state());

        OperatorRbacResult result = service(port).execute(grant("grant-1", hash('a')));

        assertThat(result.decisionStatus()).isEqualTo(OperatorRbacResult.DecisionStatus.APPLIED);
        assertThat(result.code()).isEqualTo("ROLE_GRANTED");
        assertThat(result.mutation().kind()).isEqualTo(OperatorRbacDecision.Mutation.Kind.GRANT);
        assertThat(result.mutation().catalogVersion()).isEqualTo("catalog-v1");
        assertThat(result.mutation().grantedAt()).isEqualTo(NOW);
        assertThat(result.evidence().actorPermissionIds()).containsExactlyInAnyOrder(COMMAND_PERMISSION, LOW_PERMISSION);
        assertThat(result.evidence().strictHierarchySatisfied()).isTrue();
        assertThat(port.committedResults).containsExactly(result);
    }

    @Test
    void routesAnUntrustedSubjectToTheSecurityBoundaryAndAuditsTrustedMfaDenial() {
        var untrusted = grant("untrusted", hash('b'), new OperatorRequestContext(ACTOR_ID, false, true));
        var noMfa = grant("no-mfa", hash('c'), new OperatorRequestContext(ACTOR_ID, true, false));
        var port = new RecordingPort(state());

        assertThatThrownBy(() -> service(port).execute(untrusted))
                .isInstanceOf(OperatorRbacAuthenticationRejectedException.class)
                .hasMessage("OPERATOR_AUTHENTICATION_REQUIRED");
        OperatorRbacResult result = service(port).execute(noMfa);

        assertThat(result.code()).isEqualTo("OPERATOR_MFA_REQUIRED");
        assertThat(port.committedResults).extracting(OperatorRbacResult::decisionStatus)
                .containsExactly(OperatorRbacResult.DecisionStatus.REJECTED);
    }

    @Test
    void rejectsMissingStaleAndMisconfiguredCatalogsWithoutInferringPermissions() {
        OperatorRbacState baseline = state();
        var missingPort = new RecordingPort(copyWithCatalog(baseline, null));
        var retiredCatalog = new OperatorRbacState.Catalog(
                "catalog-v1", OperatorRbacState.Status.RETIRED,
                baseline.catalog().roles(), baseline.catalog().permissions());
        var retiredPort = new RecordingPort(copyWithCatalog(baseline, retiredCatalog));
        var misconfiguredCatalog = new OperatorRbacState.Catalog(
                "catalog-v1", OperatorRbacState.Status.ACTIVE,
                baseline.catalog().roles(), Set.of(LOW_PERMISSION));
        var misconfiguredPort = new RecordingPort(copyWithCatalog(baseline, misconfiguredCatalog));
        var unknownMappedPermissionCatalog = new OperatorRbacState.Catalog(
                "catalog-v1",
                OperatorRbacState.Status.ACTIVE,
                Map.of(
                        HIGH_ROLE, baseline.catalog().roles().get(HIGH_ROLE),
                        LOW_ROLE, role(LOW_ROLE, 10, Set.of(OTHER_PERMISSION), Set.of())),
                Set.of(COMMAND_PERMISSION, LOW_PERMISSION));
        var unknownMappingPort = new RecordingPort(copyWithCatalog(baseline, unknownMappedPermissionCatalog));

        assertThat(service(missingPort).execute(grant("missing", hash('d'))).code())
                .isEqualTo("RBAC_CATALOG_UNAVAILABLE");
        assertThat(service(retiredPort).execute(grant("retired", hash('e'))).code())
                .isEqualTo("RBAC_CATALOG_UNAVAILABLE");
        assertThat(service(misconfiguredPort).execute(grant("misconfigured", hash('f'))).code())
                .isEqualTo("RBAC_PERMISSION_CONFIGURATION_INVALID");
        assertThat(service(unknownMappingPort).execute(grant("unknown-mapping", hash('a'))).code())
                .isEqualTo("RBAC_CATALOG_INVALID");
    }

    @Test
    void excludesAssignmentsFromAnotherCatalogAndAtTheExactExpiryBoundary() {
        OperatorRbacState baseline = state();
        var staleAssignment = assignment(ACTOR_ID, HIGH_ROLE, "catalog-v0", NOW.minusSeconds(60), null, null);
        var expiredAssignment = assignment(ACTOR_ID, HIGH_ROLE, "catalog-v1", NOW.minusSeconds(60), NOW, null);

        for (OperatorRbacState.Assignment ineffective : List.of(staleAssignment, expiredAssignment)) {
            var port = new RecordingPort(new OperatorRbacState(
                    baseline.catalog(), baseline.actor(), baseline.target(), List.of(ineffective), List.of(), null));

            OperatorRbacResult result = service(port).execute(grant(UUID.randomUUID().toString(), hash('1')));

            assertThat(result.code()).isEqualTo("RBAC_PERMISSION_DENIED");
            assertThat(result.mutation()).isNull();
        }
    }

    @Test
    void rejectsEqualHierarchyPermissionEscalationAndNonDelegablePermissionsSeparately() {
        OperatorRbacState baseline = state();
        var equalRole = role(LOW_ROLE, 100, Set.of(LOW_PERMISSION), Set.of(LOW_PERMISSION));
        var equalCatalog = catalog(Map.of(HIGH_ROLE, baseline.catalog().roles().get(HIGH_ROLE), LOW_ROLE, equalRole));
        assertThat(service(new RecordingPort(copyWithCatalog(baseline, equalCatalog)))
                        .execute(grant("equal", hash('2'))).code())
                .isEqualTo("ROLE_HIERARCHY_VIOLATION");

        var escalatingRole = role(LOW_ROLE, 10, Set.of(OTHER_PERMISSION), Set.of());
        var escalatingCatalog = catalog(Map.of(HIGH_ROLE, baseline.catalog().roles().get(HIGH_ROLE), LOW_ROLE, escalatingRole));
        assertThat(service(new RecordingPort(copyWithCatalog(baseline, escalatingCatalog)))
                        .execute(grant("escalating", hash('3'))).code())
                .isEqualTo("PERMISSION_ESCALATION_REJECTED");

        var actorRole = role(HIGH_ROLE, 100, Set.of(COMMAND_PERMISSION, LOW_PERMISSION), Set.of(COMMAND_PERMISSION));
        var nonDelegableCatalog = catalog(Map.of(HIGH_ROLE, actorRole, LOW_ROLE,
                role(LOW_ROLE, 10, Set.of(LOW_PERMISSION), Set.of())));
        assertThat(service(new RecordingPort(copyWithCatalog(baseline, nonDelegableCatalog)))
                        .execute(grant("non-delegable", hash('4'))).code())
                .isEqualTo("PERMISSION_NOT_DELEGABLE");
    }

    @Test
    void returnsANoOpForAnAlreadyEffectiveTargetRole() {
        OperatorRbacState baseline = state();
        OperatorRbacState withExisting = new OperatorRbacState(
                baseline.catalog(), baseline.actor(), baseline.target(), baseline.actorAssignments(),
                List.of(assignment(TARGET_ID, LOW_ROLE, "catalog-v1", NOW.minusSeconds(1), null, null)), null);

        OperatorRbacResult result = service(new RecordingPort(withExisting)).execute(grant("existing", hash('5')));

        assertThat(result.decisionStatus()).isEqualTo(OperatorRbacResult.DecisionStatus.NO_OP);
        assertThat(result.code()).isEqualTo("ROLE_ALREADY_ASSIGNED");
    }

    @Test
    void revokesAnEffectiveLowerAssignmentWithTheSameAuthorizationBoundary() {
        OperatorRbacState baseline = state();
        OperatorRbacState.Assignment selected = new OperatorRbacState.Assignment(
                ASSIGNMENT_ID, TARGET_ID, LOW_ROLE, "catalog-v1", NOW.minusSeconds(60), null, null);
        OperatorRbacState withSelected = new OperatorRbacState(
                baseline.catalog(), baseline.actor(), baseline.target(), baseline.actorAssignments(),
                List.of(selected), selected);

        OperatorRbacResult result = service(new RecordingPort(withSelected)).execute(revoke("revoke-1", hash('6')));

        assertThat(result.decisionStatus()).isEqualTo(OperatorRbacResult.DecisionStatus.APPLIED);
        assertThat(result.code()).isEqualTo("ROLE_REVOKED");
        assertThat(result.mutation().assignmentId()).isEqualTo(ASSIGNMENT_ID);
        assertThat(result.mutation().revokedAt()).isEqualTo(NOW);
    }

    @Test
    void replaysTheSameGlobalIdempotencyKeyAndConflictsOnAnotherPayload() {
        var port = new RecordingPort(state());
        OperatorRbacCommand original = grant("global-key", hash('7'));

        OperatorRbacResult first = service(port).execute(original);
        OperatorRbacResult replay = service(port).execute(original);

        assertThat(replay).isEqualTo(first);
        assertThat(port.decisions).isEqualTo(1);
        assertThatThrownBy(() -> service(port).execute(grant("global-key", hash('8'))))
                .isInstanceOf(OperatorRbacIdempotencyConflictException.class)
                .hasMessage("OPERATOR_RBAC_IDEMPOTENCY_CONFLICT");
        assertThat(port.committedResults).hasSize(1);
    }

    @Test
    void replaysAGrantAfterItsAssignmentExpiryInsteadOfReevaluatingTheOldCommand() {
        var port = new RecordingPort(state());
        OperatorRbacCommand command = grant("expiry-replay", hash('0'));

        OperatorRbacResult first = service(port).execute(command);
        var laterService = new OperatorRbacCommandService(
                port, Clock.fixed(command.expiresAt().plusSeconds(1), ZoneOffset.UTC));
        OperatorRbacResult replay = laterService.execute(command);

        assertThat(replay).isEqualTo(first);
        assertThat(port.decisions).isEqualTo(1);
    }

    @Test
    void serializesConcurrentDuplicateCommandsIntoOneDecisionAndOneAuditResult() throws Exception {
        var port = new RecordingPort(state());
        OperatorRbacCommand command = grant("concurrent", hash('9'));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service(port).execute(command));
            var second = executor.submit(() -> service(port).execute(command));

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
        assertThat(port.decisions).isEqualTo(1);
        assertThat(port.committedResults).hasSize(1);
    }

    private static OperatorRbacCommandService service(RecordingPort port) {
        return new OperatorRbacCommandService(port, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OperatorRbacCommand grant(String key, String hash) {
        return grant(key, hash, new OperatorRequestContext(ACTOR_ID, true, true));
    }

    private static OperatorRbacCommand grant(String key, String hash, OperatorRequestContext context) {
        return new OperatorRbacCommand(
                OperatorRbacCommand.Type.GRANT, context, TARGET_ID, LOW_ROLE, null, COMMAND_PERMISSION, "catalog-v1",
                NOW.plusSeconds(3600), "ACCESS_REVIEW", uuid(41), key, hash);
    }

    private static OperatorRbacCommand revoke(String key, String hash) {
        return new OperatorRbacCommand(
                OperatorRbacCommand.Type.REVOKE, new OperatorRequestContext(ACTOR_ID, true, true),
                TARGET_ID, null, ASSIGNMENT_ID, COMMAND_PERMISSION, "catalog-v1", null,
                "ACCESS_REVIEW", uuid(42), key, hash);
    }

    private static OperatorRbacState state() {
        OperatorRbacState.Role actorRole = role(
                HIGH_ROLE, 100, Set.of(COMMAND_PERMISSION, LOW_PERMISSION), Set.of(COMMAND_PERMISSION, LOW_PERMISSION));
        OperatorRbacState.Role targetRole = role(LOW_ROLE, 10, Set.of(LOW_PERMISSION), Set.of());
        return new OperatorRbacState(
                catalog(Map.of(HIGH_ROLE, actorRole, LOW_ROLE, targetRole)),
                new OperatorRbacState.Operator(ACTOR_ID, true, true),
                new OperatorRbacState.Operator(TARGET_ID, true, true),
                List.of(assignment(ACTOR_ID, HIGH_ROLE, "catalog-v1", NOW.minusSeconds(60), null, null)),
                List.of(),
                null);
    }

    private static OperatorRbacState copyWithCatalog(
            OperatorRbacState state, OperatorRbacState.Catalog catalog) {
        return new OperatorRbacState(catalog, state.actor(), state.target(), state.actorAssignments(),
                state.targetAssignments(), state.selectedAssignment());
    }

    private static OperatorRbacState.Catalog catalog(Map<UUID, OperatorRbacState.Role> roles) {
        return new OperatorRbacState.Catalog(
                "catalog-v1", OperatorRbacState.Status.ACTIVE, roles,
                Set.of(COMMAND_PERMISSION, LOW_PERMISSION, OTHER_PERMISSION));
    }

    private static OperatorRbacState.Role role(
            UUID id, int rank, Set<UUID> permissions, Set<UUID> delegable) {
        return new OperatorRbacState.Role(id, true, rank, permissions, delegable);
    }

    private static OperatorRbacState.Assignment assignment(
            UUID operatorId,
            UUID roleId,
            String catalogVersion,
            Instant grantedAt,
            Instant expiresAt,
            Instant revokedAt) {
        return new OperatorRbacState.Assignment(
                UUID.randomUUID(), operatorId, roleId, catalogVersion, grantedAt, expiresAt, revokedAt);
    }

    private static String hash(char character) {
        return String.valueOf(character).repeat(64);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("10000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private static final class RecordingPort implements OperatorRbacCommandPort {
        private final OperatorRbacState state;
        private final Map<String, Receipt> receipts = new HashMap<>();
        private final List<OperatorRbacResult> committedResults = new ArrayList<>();
        private int decisions;

        private RecordingPort(OperatorRbacState state) {
            this.state = state;
        }

        @Override
        public synchronized OperatorRbacResult executeAtomically(
                OperatorRbacCommand command,
                Instant evaluatedAt,
                OperatorRbacDecision decision) {
            Receipt receipt = receipts.get(command.idempotencyKey());
            if (receipt != null) {
                if (!receipt.requestHash.equals(command.requestHash())) {
                    throw new OperatorRbacIdempotencyConflictException();
                }
                return receipt.result;
            }
            decisions++;
            OperatorRbacResult result = decision.decide(state);
            receipts.put(command.idempotencyKey(), new Receipt(command.requestHash(), result));
            committedResults.add(result);
            return result;
        }
    }

    private record Receipt(String requestHash, OperatorRbacResult result) {}
}
