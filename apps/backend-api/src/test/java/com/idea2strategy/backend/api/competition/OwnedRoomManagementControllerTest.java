package com.idea2strategy.backend.api.competition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.OwnedRoomManagementQueryService;
import com.idea2strategy.backend.application.competition.OwnedRoomManagementQueryPort;
import com.idea2strategy.backend.application.competition.OwnedRoomManagementView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnedRoomManagementControllerTest {
    private static final UUID ACCOUNT_ID = id(1);
    private static final UUID ROOM_ID = id(2);
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    @Test
    void returnsOwnerConfigurationInvitationAndParticipationEvidence() throws Exception {
        var view = new OwnedRoomManagementView(
                ROOM_ID, "Owner room", "SECRET", "RECRUITING", NOW, id(3), Map.of(),
                BigDecimal.valueOf(10_000), 25, 2, "RELEASE_SLOT", 60, 3, id(4), id(5),
                NOW, NOW.plusSeconds(60), NOW.plusSeconds(600), NOW.plusSeconds(300),
                NOW.plusSeconds(1200), NOW.plusSeconds(1800), "Asia/Seoul",
                List.of(new OwnedRoomManagementView.Invitation(id(6), "LINK", NOW, NOW.plusSeconds(600), null, null)),
                List.of(new OwnedRoomManagementView.Participation(id(7), id(8), "Bot A", "ACTIVE", NOW)));
        var service = new OwnedRoomManagementQueryService(new OwnedRoomManagementQueryPort() {
            @Override public List<OwnedRoomManagementView> findOwnedBy(UUID owner, int limit) {
                if (!owner.equals(ACCOUNT_ID) || limit != 25) throw new AssertionError("principal or limit lost");
                return List.of(view);
            }
            @Override public Optional<OwnedRoomManagementView> findOwnedById(UUID owner, UUID roomId) {
                return owner.equals(ACCOUNT_ID) && roomId.equals(ROOM_ID) ? Optional.of(view) : Optional.empty();
            }
        }, () -> ACCOUNT_ID);
        var mvc = MockMvcBuilders.standaloneSetup(new OwnedRoomManagementController(service)).build();

        mvc.perform(get("/api/v1/competition/rooms/mine").param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].roomId").value(ROOM_ID.toString()))
                .andExpect(jsonPath("$.items[0].accessType").value("SECRET"))
                .andExpect(jsonPath("$.items[0].invitations[0].credentialType").value("LINK"))
                .andExpect(jsonPath("$.items[0].participations[0].anonymousAlias").value("Bot A"));
        mvc.perform(get("/api/v1/competition/rooms/mine/{roomId}", ROOM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(ROOM_ID.toString()));
        mvc.perform(get("/api/v1/competition/rooms/mine/{roomId}", id(99)))
                .andExpect(status().isNotFound());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("98000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}
