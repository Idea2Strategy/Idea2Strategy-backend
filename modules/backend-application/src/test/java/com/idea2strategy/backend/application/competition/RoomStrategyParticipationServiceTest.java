package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.strategy.BasicStrategyCatalog;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommandService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleasePreparationCommand;
import com.idea2strategy.backend.application.strategy.StrategyValidationRunQueryPort;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalogQueryPort;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RoomStrategyParticipationServiceTest {
    private static final UUID ROOM_ID = UUID.fromString("10000000-0000-4000-8000-000000000080");
    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-4000-8000-000000000080");
    private static final UUID VALIDATION_ID = UUID.fromString("30000000-0000-4000-8000-000000000080");
    private static final UUID STRATEGY_ID = UUID.fromString("40000000-0000-4000-8000-000000000080");
    private static final UUID CATALOG_ID = UUID.fromString("50000000-0000-4000-8000-000000000080");
    private static final UUID BOT_ID = UUID.fromString("60000000-0000-4000-8000-000000000080");
    private static final UUID FEE_ID = UUID.fromString("70000000-0000-4000-8000-000000000080");
    private static final UUID BUFFER_ID = UUID.fromString("80000000-0000-4000-8000-000000000080");
    private static final Instant ADMITTED_AT = Instant.parse("2026-08-02T01:00:00Z");
    private static final Instant EVALUATION_START = Instant.parse("2026-08-03T01:00:00Z");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void createsANewBotFromTheExactValidationUsingLockedRoomRules() {
        var admissionService = mock(RoomParticipationAdmissionService.class);
        var provisioningPort = mock(RoomStrategyBotProvisioningPort.class);
        var releaseService = mock(ImmutableStrategyReleaseCommandService.class);
        var catalogService = mock(BasicStrategyCatalogQueryService.class);
        var catalog = mock(BasicStrategyCatalog.class);
        var release = mock(ImmutableStrategyRelease.class);
        StrategyValidationRun validation = new StrategyValidationRun(
                VALIDATION_ID, STRATEGY_ID, OWNER_ID, null, 7, HASH, CATALOG_ID,
                StrategyValidationStatus.VALID, List.of(), ADMITTED_AT.minusSeconds(2), ADMITTED_AT.minusSeconds(1));
        StrategyValidationRunQueryPort validations = (id, owner) -> Optional.of(validation)
                .filter(value -> id.equals(VALIDATION_ID) && owner.equals(OWNER_ID));
        when(catalogService.getPublished("basic/v1", "schema/v1", "catalog/v1")).thenReturn(catalog);
        when(releaseService.prepare(eq(VALIDATION_ID), eq(catalog), any(), eq(ADMITTED_AT))).thenReturn(release);
        when(provisioningPort.provision(release, VALIDATION_ID, 7, HASH, EVALUATION_START)).thenReturn(BOT_ID);
        when(admissionService.admit(eq(ROOM_ID), eq("ALPHA"), any())).thenAnswer(invocation -> {
            RoomBotProvisioningAction action = invocation.getArgument(2);
            UUID botId = action.provision(new RoomParticipationAdmissionContext(
                    ROOM_ID,
                    OWNER_ID,
                    ADMITTED_AT,
                    EVALUATION_START,
                    RoomSubmissionTiming.WAIT_UNTIL_EVALUATION,
                    new RoomBotLaunchRules(new BigDecimal("250000.00"), FEE_ID, BUFFER_ID, "room-precision/v2")));
            return new RoomParticipationAdmission(
                    UUID.randomUUID(), ROOM_ID, botId, OWNER_ID, "ALPHA", ADMITTED_AT);
        });
        StrategyReleaseInputCatalogQueryPort releaseInputs = observedAt -> new StrategyReleaseInputCatalog(
                List.of(new StrategyReleaseInputCatalog.ExecutionPolicy(
                        "competition-policy-v1", "broker/v3", "accounting/v4", "room-precision/v2",
                        FEE_ID, 20, BUFFER_ID, 1,
                        java.time.LocalDate.parse("2025-01-01"), java.time.LocalDate.parse("2025-12-31"),
                        "market-bars/1", ADMITTED_AT.minusSeconds(60))),
                List.of(), observedAt);
        var service = new RoomStrategyParticipationService(
                admissionService,
                provisioningPort,
                releaseService,
                catalogService,
                validations,
                releaseInputs,
                () -> OWNER_ID,
                () -> BOT_ID);

        var result = service.join(new JoinRoomWithStrategyCommand(
                ROOM_ID,
                VALIDATION_ID,
                "ALPHA",
                "basic/v1",
                "schema/v1",
                "catalog/v1",
                8_000));

        assertThat(result.botId()).isEqualTo(BOT_ID);
        var preparation = ArgumentCaptor.forClass(ImmutableStrategyReleasePreparationCommand.class);
        verify(releaseService).prepare(eq(VALIDATION_ID), eq(catalog), preparation.capture(), eq(ADMITTED_AT));
        assertThat(preparation.getValue().botId()).isEqualTo(BOT_ID);
        assertThat(preparation.getValue().initialCashAmount()).isEqualByComparingTo("250000.00");
        assertThat(preparation.getValue().feePolicyId()).isEqualTo(FEE_ID);
        assertThat(preparation.getValue().buyingPowerBufferPolicyId()).isEqualTo(BUFFER_ID);
        assertThat(preparation.getValue().precisionRulesVersion()).isEqualTo("room-precision/v2");
        assertThat(preparation.getValue().budgetCapBps()).isEqualTo(8_000);
        assertThat(preparation.getValue().brokerRulesVersion()).isEqualTo("broker/v3");
        assertThat(preparation.getValue().accountingRulesVersion()).isEqualTo("accounting/v4");
    }
}
