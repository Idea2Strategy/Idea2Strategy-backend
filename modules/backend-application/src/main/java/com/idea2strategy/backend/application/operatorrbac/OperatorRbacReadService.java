package com.idea2strategy.backend.application.operatorrbac;

import static com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadModels.DecisionStatus.REJECTED;
import static com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadModels.DecisionStatus.SUCCEEDED;
import static com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadModels.Kind.ASSIGNMENTS;
import static com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadModels.Kind.CATALOG;
import static com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadModels.Kind.SELF;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class OperatorRbacReadService {
    private static final String FORBIDDEN = "OPERATOR_RBAC_READ_FORBIDDEN";
    private static final String CONFLICT = "OPERATOR_RBAC_CATALOG_VERSION_CONFLICT";

    private final OperatorRbacReadPort port;
    private final OperatorRbacReadGuardCatalog guards;
    private final Clock clock;

    public OperatorRbacReadService(
            OperatorRbacReadPort port,
            OperatorRbacReadGuardCatalog guards,
            Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.guards = Objects.requireNonNull(guards, "guards");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OperatorRbacReadResult.Self readSelf(
            OperatorRequestContext context, UUID correlationId) {
        Instant now = clock.instant();
        var state = trustedActor(context, correlationId, now);
        if (state.activeCatalogVersion() == null) {
            reject(SELF, context.operatorId(), context.operatorId(), correlationId,
                    null, null, "OPERATOR_RBAC_UNAVAILABLE", now,
                    OperatorRbacReadRejectedException.Reason.FORBIDDEN,
                    null, state.effectivePermissionIds(), false, context.mfaCompleted());
        }
        var view = state.self().withCurrentMfa(
                context.mfaCompleted(), context.mfaAuthenticatedAt());
        record(SELF, context.operatorId(), context.operatorId(), correlationId,
                null, state.activeCatalogVersion(), SUCCEEDED, "OPERATOR_SELF_READ", now,
                null, state.effectivePermissionIds(), false, context.mfaCompleted());
        return new OperatorRbacReadResult.Self(view, correlationId);
    }

    public OperatorRbacReadResult.Catalog readCatalog(
            OperatorRequestContext context, UUID correlationId) {
        Instant now = clock.instant();
        var state = trustedActor(context, correlationId, now);
        var guard = guards.activeGuard();
        authorize(CATALOG, context, context.operatorId(), correlationId, state,
                guard.catalogReadPermissionId(), guard.catalogReadMfaRequired(),
                guard.expectedCatalogVersion(), now);
        var view = port.loadCatalog(state.activeCatalogVersion(), now).orElseGet(() -> {
            reject(CATALOG, context.operatorId(), context.operatorId(), correlationId,
                    guard.expectedCatalogVersion(), state.activeCatalogVersion(), CONFLICT, now,
                    OperatorRbacReadRejectedException.Reason.CONFLICT,
                    guard.catalogReadPermissionId(), state.effectivePermissionIds(),
                    guard.catalogReadMfaRequired(), context.mfaCompleted());
            throw new AssertionError("unreachable");
        });
        record(CATALOG, context.operatorId(), context.operatorId(), correlationId,
                guard.expectedCatalogVersion(), state.activeCatalogVersion(), SUCCEEDED,
                "OPERATOR_RBAC_CATALOG_READ", now, guard.catalogReadPermissionId(),
                state.effectivePermissionIds(), guard.catalogReadMfaRequired(),
                context.mfaCompleted());
        return new OperatorRbacReadResult.Catalog(view, correlationId);
    }

    public OperatorRbacReadResult.Assignments readAssignments(
            OperatorRequestContext context, UUID targetOperatorId, UUID correlationId) {
        Objects.requireNonNull(targetOperatorId, "targetOperatorId");
        Instant now = clock.instant();
        var state = trustedActor(context, correlationId, now);
        var guard = guards.activeGuard();
        authorize(ASSIGNMENTS, context, targetOperatorId, correlationId, state,
                guard.assignmentReadPermissionId(), guard.assignmentReadMfaRequired(),
                guard.expectedCatalogVersion(), now);
        var view = port.loadAssignments(targetOperatorId, state.activeCatalogVersion(), now)
                .orElseGet(() -> {
                    reject(ASSIGNMENTS, context.operatorId(), targetOperatorId, correlationId,
                            guard.expectedCatalogVersion(), state.activeCatalogVersion(),
                            "OPERATOR_NOT_FOUND", now,
                            OperatorRbacReadRejectedException.Reason.NOT_FOUND,
                            guard.assignmentReadPermissionId(), state.effectivePermissionIds(),
                            guard.assignmentReadMfaRequired(), context.mfaCompleted());
                    throw new AssertionError("unreachable");
                });
        record(ASSIGNMENTS, context.operatorId(), targetOperatorId, correlationId,
                guard.expectedCatalogVersion(), state.activeCatalogVersion(), SUCCEEDED,
                "OPERATOR_ASSIGNMENTS_READ", now, guard.assignmentReadPermissionId(),
                state.effectivePermissionIds(), guard.assignmentReadMfaRequired(),
                context.mfaCompleted());
        return new OperatorRbacReadResult.Assignments(view, correlationId);
    }

    private OperatorRbacReadModels.ActorState trustedActor(
            OperatorRequestContext context, UUID correlationId, Instant now) {
        if (context == null || !context.trustedExternalSubject()) {
            throw new OperatorRbacReadRejectedException(
                    OperatorRbacReadRejectedException.Reason.UNAUTHENTICATED,
                    "OPERATOR_AUTHENTICATION_REQUIRED", correlationId);
        }
        var state = Objects.requireNonNull(
                port.loadActorState(context.operatorId(), now), "actorState");
        if (!state.active() || !context.operatorId().equals(state.self().operatorId())) {
            throw new OperatorRbacReadRejectedException(
                    OperatorRbacReadRejectedException.Reason.UNAUTHENTICATED,
                    "OPERATOR_AUTHENTICATION_REQUIRED", correlationId);
        }
        return state;
    }

    private void authorize(
            OperatorRbacReadModels.Kind kind,
            OperatorRequestContext context,
            UUID target,
            UUID correlationId,
            OperatorRbacReadModels.ActorState state,
            UUID requiredPermission,
            boolean mfaRequired,
            String expectedCatalogVersion,
            Instant now) {
        if (!state.effectivePermissionIds().contains(requiredPermission)
                || (mfaRequired && !context.mfaCompleted())) {
            reject(kind, context.operatorId(), target, correlationId, expectedCatalogVersion,
                    state.activeCatalogVersion(), FORBIDDEN, now,
                    OperatorRbacReadRejectedException.Reason.FORBIDDEN,
                    requiredPermission, state.effectivePermissionIds(), mfaRequired,
                    context.mfaCompleted());
        }
        if (!expectedCatalogVersion.equals(state.activeCatalogVersion())) {
            reject(kind, context.operatorId(), target, correlationId, expectedCatalogVersion,
                    state.activeCatalogVersion(), CONFLICT, now,
                    OperatorRbacReadRejectedException.Reason.CONFLICT,
                    requiredPermission, state.effectivePermissionIds(), mfaRequired,
                    context.mfaCompleted());
        }
    }

    private void reject(
            OperatorRbacReadModels.Kind kind,
            UUID actor,
            UUID target,
            UUID correlationId,
            String expectedCatalogVersion,
            String resolvedCatalogVersion,
            String code,
            Instant now,
            OperatorRbacReadRejectedException.Reason reason,
            UUID requiredPermissionId,
            java.util.Set<UUID> effectivePermissionIds,
            boolean mfaRequired,
            boolean currentMfa) {
        record(kind, actor, target, correlationId, expectedCatalogVersion,
                resolvedCatalogVersion, REJECTED, code, now,
                requiredPermissionId, effectivePermissionIds, mfaRequired, currentMfa);
        throw new OperatorRbacReadRejectedException(reason, code, correlationId);
    }

    private void record(
            OperatorRbacReadModels.Kind kind,
            UUID actor,
            UUID target,
            UUID correlationId,
            String expectedCatalogVersion,
            String resolvedCatalogVersion,
            OperatorRbacReadModels.DecisionStatus status,
            String code,
            Instant now,
            UUID requiredPermissionId,
            java.util.Set<UUID> effectivePermissionIds,
            boolean mfaRequired,
            boolean currentMfa) {
        port.recordDecision(new OperatorRbacReadModels.AuditDecision(
                kind, actor, target, correlationId, expectedCatalogVersion,
                resolvedCatalogVersion, status, code, now,
                requiredPermissionId, effectivePermissionIds, mfaRequired, currentMfa));
    }
}
