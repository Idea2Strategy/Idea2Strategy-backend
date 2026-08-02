package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoomTerminationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");
    private static final UUID ROOM_ID = id(1);
    private static final UUID PARTICIPATION_ID = id(2);
    private static final UUID ACCOUNT_ID = id(3);
    private static final UUID OPERATOR_ID = id(4);

    @Test
    void suppliesTheAuthenticatedAccountAndExplicitExitAction() {
        var port = new StubPort();
        var service = new UserRoomTerminationService(port, () -> ACCOUNT_ID, fixedClock());

        service.withdraw(ROOM_ID, PARTICIPATION_ID, ParticipationExitAction.STOP, "USER_REQUESTED");

        assertThat(port.ownerId).isEqualTo(ACCOUNT_ID);
        assertThat(port.action).isEqualTo(ParticipationExitAction.STOP);
        assertThat(port.at).isEqualTo(NOW);
    }

    @Test
    void suppliesTheCreatorWithoutAnExpulsionReason() {
        var port = new StubPort();
        var service = new UserRoomTerminationService(port, () -> ACCOUNT_ID, fixedClock());

        service.expel(ROOM_ID, PARTICIPATION_ID);

        assertThat(port.ownerId).isEqualTo(ACCOUNT_ID);
        assertThat(port.at).isEqualTo(NOW);
    }

    @Test
    void requiresAnAuthorizedOperatorAndBoundedReason() {
        var port = new StubPort();
        var unauthorized = new PlatformRoomInvalidationService(port, Optional::empty, fixedClock());
        assertThatThrownBy(() -> unauthorized.invalidate(ROOM_ID, "LEGAL_REQUIREMENT"))
                .isInstanceOf(OperatorAuthorizationException.class);

        var authorized = new PlatformRoomInvalidationService(port, () -> Optional.of(OPERATOR_ID), fixedClock());
        authorized.invalidate(ROOM_ID, "OFFICIAL_LEDGER_INTEGRITY");
        assertThat(port.operatorId).isEqualTo(OPERATOR_ID);
        assertThatThrownBy(() -> authorized.invalidate(ROOM_ID, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authorized.invalidate(ROOM_ID, "CREATOR_REQUESTED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legal, safety, or ledger-integrity");
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("88000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final class StubPort implements RoomTerminationPort {
        UUID ownerId;
        UUID operatorId;
        ParticipationExitAction action;
        Instant at;

        @Override
        public RoomTerminationResult withdrawOwned(
                UUID roomId, UUID participationId, UUID ownerAccountId, ParticipationExitAction action,
                String reasonCode, Instant occurredAt) {
            this.ownerId = ownerAccountId;
            this.action = action;
            this.at = occurredAt;
            return new RoomTerminationResult(roomId, 1, occurredAt);
        }

        @Override
        public RoomTerminationResult cancelOwned(
                UUID roomId, UUID creatorAccountId, String reasonCode, Instant occurredAt) {
            return new RoomTerminationResult(roomId, 0, occurredAt);
        }

        @Override
        public RoomTerminationResult expelOwned(
                UUID roomId, UUID participationId, UUID creatorAccountId, Instant occurredAt) {
            this.ownerId = creatorAccountId;
            this.at = occurredAt;
            return new RoomTerminationResult(roomId, 1, occurredAt);
        }

        @Override
        public RoomTerminationResult invalidate(
                UUID roomId, UUID operatorId, String reasonCode, Instant occurredAt) {
            this.operatorId = operatorId;
            return new RoomTerminationResult(roomId, 0, occurredAt);
        }
    }
}
