package com.idea2strategy.backend.api.competition;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.competition.PublicRoomDiscoveryService;
import com.idea2strategy.backend.application.competition.PublicRoomItem;
import com.idea2strategy.backend.domain.competition.RoomOrganizerType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicRoomDiscoveryControllerTest {
    @Test
    void returnsRecruitingPublicRoomsAndPaginationMetadata() throws Exception {
        UUID roomId = UUID.fromString("73000000-0000-4000-8000-000000000001");
        var item = new PublicRoomItem(
                roomId,
                "August public room",
                RoomOrganizerType.USER,
                Instant.parse("2026-08-02T00:00:00Z"),
                Instant.parse("2026-08-02T01:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z"),
                10,
                1);
        var service = new PublicRoomDiscoveryService((query, before, beforeId, limit) -> List.of(item));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PublicRoomDiscoveryController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/competition/rooms/public").param("q", "August").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(roomId.toString()))
                .andExpect(jsonPath("$.items[0].name").value("August public room"))
                .andExpect(jsonPath("$.items[0].organizerType").value("USER"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void rejectsAnInvalidLimit() throws Exception {
        var service = new PublicRoomDiscoveryService((query, before, beforeId, limit) -> List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PublicRoomDiscoveryController(service))
                .setControllerAdvice(new CompetitionRoomExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/competition/rooms/public").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid competition room"));
    }
}
