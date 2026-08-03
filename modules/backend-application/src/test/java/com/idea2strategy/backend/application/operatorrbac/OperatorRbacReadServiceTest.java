package com.idea2strategy.backend.application.operatorrbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperatorRbacReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T06:00:00Z");
    private static final Instant MFA_AT = NOW.minusSeconds(60);
    private static final UUID ACTOR = id(1);
    private static final UUID TARGET = id(2);
    private static final UUID CORRELATION = id(3);
    private static final UUID CATALOG_PERMISSION = id(4);
    private static final UUID ASSIGNMENT_PERMISSION = id(5);
    private static final String VERSION = "catalog-v1";

    @Test
    void selfRequiresOnlyTheTrustedActiveActorAndReturnsSignedMfaFreshness() {
        RecordingPort port = new RecordingPort();
        OperatorRbacReadService service = service(port);

        var result = service.readSelf(
                new OperatorRequestContext(ACTOR, true, true, MFA_AT), CORRELATION);

        assertThat(result.view().operatorId()).isEqualTo(ACTOR);
        assertThat(result.view().currentMfa()).isTrue();
        assertThat(result.view().mfaAuthenticatedAt()).isEqualTo(MFA_AT);
        assertThat(port.calls).containsExactly("actor", "audit:OPERATOR_SELF_READ");
        assertThat(port.audits.getFirst().decisionStatus())
                .isEqualTo(OperatorRbacReadModels.DecisionStatus.SUCCEEDED);
    }

    @Test
    void untrustedActorIsRejectedBeforeAnyDatabaseRead() {
        RecordingPort port = new RecordingPort();

        assertThatThrownBy(() -> service(port).readSelf(
                new OperatorRequestContext(ACTOR, false, false), CORRELATION))
                .isInstanceOfSatisfying(OperatorRbacReadRejectedException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(OperatorRbacReadRejectedException.Reason.UNAUTHENTICATED);
                    assertThat(exception.correlationId()).isEqualTo(CORRELATION);
                });
        assertThat(port.calls).isEmpty();
    }

    @Test
    void catalogPermissionAndMfaDenialsAreGenericAndPrecedeVersionDisclosure() {
        RecordingPort port = new RecordingPort();
        port.version = "new-secret-version";

        assertGenericForbidden(() -> service(port).readCatalog(
                new OperatorRequestContext(ACTOR, true, true, MFA_AT), CORRELATION));
        assertThat(port.calls).containsExactly("actor", "audit:OPERATOR_RBAC_READ_FORBIDDEN");
        assertThat(port.calls).doesNotContain("catalog");

        port.reset();
        port.permissions.add(CATALOG_PERMISSION);
        OperatorRbacReadGuardCatalog mfaGuard = () -> new OperatorRbacReadGuardCatalog.Guard(
                VERSION, CATALOG_PERMISSION, true, ASSIGNMENT_PERMISSION, true);
        assertGenericForbidden(() -> new OperatorRbacReadService(
                port, mfaGuard, Clock.fixed(NOW, ZoneOffset.UTC)).readCatalog(
                new OperatorRequestContext(ACTOR, true, false), CORRELATION));
        assertThat(port.calls).doesNotContain("catalog");
    }

    @Test
    void distinctGuardsRunBeforeCatalogOrTargetLookupAndAuthorizedReadsSucceed() {
        RecordingPort port = new RecordingPort();
        port.permissions.add(CATALOG_PERMISSION);

        assertThat(service(port).readCatalog(
                new OperatorRequestContext(ACTOR, true, false), CORRELATION).view().catalogVersion())
                .isEqualTo(VERSION);
        assertThat(port.calls).containsSubsequence("actor", "catalog", "audit:OPERATOR_RBAC_CATALOG_READ");

        port.reset();
        port.permissions.add(CATALOG_PERMISSION);
        assertGenericForbidden(() -> service(port).readAssignments(
                new OperatorRequestContext(ACTOR, true, true, MFA_AT), TARGET, CORRELATION));
        assertThat(port.calls).doesNotContain("assignments");

        port.reset();
        port.permissions.add(ASSIGNMENT_PERMISSION);
        port.version = "catalog-v2";
        assertThatThrownBy(() -> service(port).readAssignments(
                new OperatorRequestContext(ACTOR, true, true, MFA_AT), TARGET, CORRELATION))
                .isInstanceOfSatisfying(OperatorRbacReadRejectedException.class, exception ->
                        assertThat(exception.reason())
                                .isEqualTo(OperatorRbacReadRejectedException.Reason.CONFLICT));
        assertThat(port.calls).doesNotContain("assignments");

        port.reset();
        port.permissions.add(ASSIGNMENT_PERMISSION);
        assertThat(service(port).readAssignments(
                new OperatorRequestContext(ACTOR, true, true, MFA_AT), TARGET, CORRELATION)
                .view().operatorId()).isEqualTo(TARGET);
    }

    @Test
    void authorizedMissingTargetIsNotFoundOnlyAfterAssignmentLookup() {
        RecordingPort port = new RecordingPort();
        port.permissions.add(ASSIGNMENT_PERMISSION);
        port.assignments = Optional.empty();

        assertThatThrownBy(() -> service(port).readAssignments(
                new OperatorRequestContext(ACTOR, true, true, MFA_AT), TARGET, CORRELATION))
                .isInstanceOfSatisfying(OperatorRbacReadRejectedException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(OperatorRbacReadRejectedException.Reason.NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("OPERATOR_NOT_FOUND");
                });
        assertThat(port.calls).containsSubsequence(
                "actor", "assignments", "audit:OPERATOR_NOT_FOUND");
    }

    private static OperatorRbacReadService service(OperatorRbacReadPort port) {
        OperatorRbacReadGuardCatalog guards = () -> new OperatorRbacReadGuardCatalog.Guard(
                VERSION, CATALOG_PERMISSION, false, ASSIGNMENT_PERMISSION, true);
        return new OperatorRbacReadService(port, guards, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertGenericForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(
                OperatorRbacReadRejectedException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(OperatorRbacReadRejectedException.Reason.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("OPERATOR_RBAC_READ_FORBIDDEN");
                });
    }

    private static final class RecordingPort implements OperatorRbacReadPort {
        private String version = VERSION;
        private final Set<UUID> permissions = new LinkedHashSet<>();
        private Optional<OperatorRbacReadModels.AssignmentsView> assignments = Optional.of(
                new OperatorRbacReadModels.AssignmentsView(TARGET, List.of()));
        private final List<String> calls = new ArrayList<>();
        private final List<OperatorRbacReadModels.AuditDecision> audits = new ArrayList<>();

        @Override
        public OperatorRbacReadModels.ActorState loadActorState(UUID actorId, Instant evaluatedAt) {
            calls.add("actor");
            return new OperatorRbacReadModels.ActorState(true, version, permissions,
                    new OperatorRbacReadModels.SelfView(
                            ACTOR, version, false, null, NOW.minusSeconds(120),
                            List.of(), List.of(), List.of()));
        }

        @Override
        public Optional<OperatorRbacReadModels.CatalogView> loadCatalog(
                String catalogVersion, Instant evaluatedAt) {
            calls.add("catalog");
            return Optional.of(new OperatorRbacReadModels.CatalogView(
                    catalogVersion, List.of(), List.of(), List.of()));
        }

        @Override
        public Optional<OperatorRbacReadModels.AssignmentsView> loadAssignments(
                UUID targetOperatorId, String catalogVersion, Instant evaluatedAt) {
            calls.add("assignments");
            return assignments;
        }

        @Override
        public void recordDecision(OperatorRbacReadModels.AuditDecision decision) {
            audits.add(decision);
            calls.add("audit:" + decision.responseCode());
        }

        void reset() {
            version = VERSION;
            permissions.clear();
            assignments = Optional.of(new OperatorRbacReadModels.AssignmentsView(TARGET, List.of()));
            calls.clear();
            audits.clear();
        }
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a2200000-0000-4000-8000-" + "%012d".formatted(suffix));
    }
}
