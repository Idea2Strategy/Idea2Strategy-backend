package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UserRoomConfigurationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("64000000-0000-4000-8000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("64000000-0000-4000-8000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("64000000-0000-4000-8000-000000000003");
    private static final UUID FEE_ID = UUID.fromString("64000000-0000-4000-8000-000000000004");
    private static final UUID BUFFER_ID = UUID.fromString("64000000-0000-4000-8000-000000000005");

    @Test
    void validatesAndCanonicalizesTheWholeConfigurationBeforeUpdating() {
        var captured = new AtomicReference<RoomConfigurationUpdate>();
        var service = service(update -> {
            captured.set(update);
            return RoomConfigurationUpdateOutcome.UPDATED;
        });

        service.update(ROOM_ID, command(RoomAccessType.PUBLIC));

        assertThat(captured.get().creatorAccountId()).isEqualTo(OWNER_ID);
        assertThat(captured.get().scoringParameters()).isEqualTo("{\"minimumTrades\":5}");
        assertThat(captured.get().rulesHash()).matches("[A-Za-z0-9_-]{43}");
        assertThat(captured.get().observedAt()).isEqualTo(NOW);
    }

    @Test
    void mapsOwnershipAccessAndRecruitmentFailures() {
        assertThatThrownBy(() -> service(update -> RoomConfigurationUpdateOutcome.NOT_FOUND_OR_NOT_OWNED)
                        .update(ROOM_ID, command(RoomAccessType.PUBLIC)))
                .isInstanceOf(RoomConfigurationAccessException.class);
        assertThatThrownBy(() -> service(update -> RoomConfigurationUpdateOutcome.ACCESS_TYPE_IMMUTABLE)
                        .update(ROOM_ID, command(RoomAccessType.SECRET)))
                .isInstanceOf(RoomConfigurationConflictException.class)
                .hasMessageContaining("access type");
        assertThatThrownBy(() -> service(update -> RoomConfigurationUpdateOutcome.RECRUITMENT_LOCKED)
                        .update(ROOM_ID, command(RoomAccessType.PUBLIC)))
                .isInstanceOf(RoomConfigurationConflictException.class)
                .hasMessageContaining("recruitment");
    }

    private static UserRoomConfigurationService service(RoomConfigurationPort port) {
        var catalog = new ScoringTemplateCatalogService(
                new CatalogPort(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        return new UserRoomConfigurationService(
                port, catalog, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }

    private static UpdateUserLiveRoomCommand command(RoomAccessType accessType) {
        return new UpdateUserLiveRoomCommand(
                "Updated room",
                accessType,
                TEMPLATE_ID,
                Map.of("minimumTrades", new BigDecimal("5")),
                new BigDecimal("200000.00000000"),
                8,
                2,
                "RETURN_ON_STOP",
                7200,
                10,
                FEE_ID,
                BUFFER_ID,
                new RoomSchedule(
                        NOW.plusSeconds(60),
                        NOW.plusSeconds(120),
                        NOW.plusSeconds(240),
                        NOW.plusSeconds(180),
                        NOW.plusSeconds(300),
                        NOW.plusSeconds(360),
                        "UTC"));
    }

    private static final class CatalogPort implements ScoringTemplateCatalogQueryPort {
        private final ScoringTemplateCatalogRecord record = new ScoringTemplateCatalogRecord(
                TEMPLATE_ID,
                "TOTAL_RETURN",
                "1.0.0",
                """
                {"kind":"SINGLE","calculationRulesVersion":"1.0.0",
                 "components":[{"metric":"TOTAL_RETURN","direction":"HIGHER_IS_BETTER","coefficient":1}],
                 "adjustments":[{"code":"minimumTrades","unit":"COUNT","minimum":2,"maximum":20,"scale":0}]}
                """,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NOW.minusSeconds(60),
                null);

        @Override
        public List<ScoringTemplateCatalogRecord> findSelectableAt(Instant at) {
            return List.of(record);
        }

        @Override
        public Optional<ScoringTemplateCatalogRecord> findSelectableById(UUID id, Instant at) {
            return TEMPLATE_ID.equals(id) ? Optional.of(record) : Optional.empty();
        }
    }
}
