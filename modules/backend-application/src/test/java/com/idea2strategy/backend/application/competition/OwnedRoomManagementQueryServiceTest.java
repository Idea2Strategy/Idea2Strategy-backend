package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OwnedRoomManagementQueryServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("98000000-0000-4000-8000-000000000001");

    @Test
    void scopesTheManagementListToTheCurrentAccountAndBoundsTheLimit() {
        var observedOwner = new AtomicReference<UUID>();
        var observedLimit = new AtomicReference<Integer>();
        OwnedRoomManagementQueryPort port = new OwnedRoomManagementQueryPort() {
            @Override public List<OwnedRoomManagementView> findOwnedBy(UUID owner, int limit) {
                observedOwner.set(owner);
                observedLimit.set(limit);
                return List.of();
            }
            @Override public Optional<OwnedRoomManagementView> findOwnedById(UUID owner, UUID roomId) {
                observedOwner.set(owner);
                return Optional.empty();
            }
        };
        var service = new OwnedRoomManagementQueryService(port, () -> ACCOUNT_ID);

        assertThat(service.list(50)).isEmpty();
        assertThat(observedOwner.get()).isEqualTo(ACCOUNT_ID);
        assertThat(observedLimit.get()).isEqualTo(50);
        assertThatThrownBy(() -> service.list(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(UUID.randomUUID())).isEmpty();
        assertThat(observedOwner.get()).isEqualTo(ACCOUNT_ID);
    }
}
