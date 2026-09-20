package com.idea2strategy.backend.application.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.idea2strategy.backend.application.common.CurrentOperatorPrincipal;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

public final class OfficialBacktestCompetitionRoomCreationService {
    private final OfficialBacktestRoomCommandPort commandPort;
    private final ScoringTemplateCatalogService scoringCatalog;
    private final CurrentOperatorPrincipal principal;
    private final Clock clock;
    private final Supplier<UUID> roomIdSupplier;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public OfficialBacktestCompetitionRoomCreationService(
            OfficialBacktestRoomCommandPort commandPort,
            ScoringTemplateCatalogService scoringCatalog,
            CurrentOperatorPrincipal principal,
            Clock clock,
            Supplier<UUID> roomIdSupplier) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.scoringCatalog = Objects.requireNonNull(scoringCatalog, "scoringCatalog");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.roomIdSupplier = Objects.requireNonNull(roomIdSupplier, "roomIdSupplier");
    }

    public CompetitionRoom create(CreateOfficialBacktestRoomCommand command) {
        Objects.requireNonNull(command, "command");
        UUID operatorId = Objects.requireNonNull(principal.operatorId(), "operatorId")
                .orElseThrow(OperatorAuthorizationException::new);
        var scoring = scoringCatalog.select(command.scoringTemplateVersionId(), Map.of());
        UUID roomId = roomIdSupplier.get();
        var periods = new ArrayList<BacktestEvaluationPlanDefinition.Period>();
        for (int index = 0; index < command.periods().size(); index++) {
            var period = command.periods().get(index);
            int sequence = index + 1;
            periods.add(new BacktestEvaluationPlanDefinition.Period(
                    UUID.nameUUIDFromBytes(("backtest-period.v1:" + roomId + ":" + sequence)
                            .getBytes(StandardCharsets.UTF_8)),
                    sequence,
                    period.evaluationStart(),
                    period.evaluationEnd(),
                    period.importanceWeight(),
                    period.inputSetHash(),
                    period.datasets(),
                    period.featureMaterializations()));
        }
        var plan = new BacktestEvaluationPlanDefinition(
                command.planVersion(), command.planHash(), command.commitmentHash(),
                command.commitmentNonceCiphertext(), command.nonceKeyVersion(), periods);
        String eligibility = canonical(command.eligibilityCriteria(), "eligibilityCriteria");
        String marketScope = canonical(command.marketScope(), "marketScope");
        String scoringParameters = json(new TreeMap<>(scoring.adjustments()));
        String rulesHash = hash(Map.ofEntries(
                Map.entry("scoringTemplateVersionId", scoring.template().id().toString()),
                Map.entry("scoringTemplateRulesHash", scoring.template().rulesHash()),
                Map.entry("initialCashAmount", command.initialCashAmount().toPlainString()),
                Map.entry("botParticipationLimit", command.botParticipationLimit()),
                Map.entry("perAccountBotLimit", command.perAccountBotLimit()),
                Map.entry("feePolicyId", command.feePolicyId().toString()),
                Map.entry("buyingPowerBufferPolicyId", command.buyingPowerBufferPolicyId().toString()),
                Map.entry("precisionRulesVersion", command.precisionRulesVersion()),
                Map.entry("eligibilityCriteria", eligibility),
                Map.entry("marketScope", marketScope),
                Map.entry("planHash", plan.planHash()),
                Map.entry("schedule", command.schedule().toString())));
        var room = CompetitionRoom.platformBacktest(
                roomId,
                operatorId,
                command.name(),
                command.accessType(),
                scoring.template().id(),
                command.initialCashAmount(),
                command.botParticipationLimit(),
                command.perAccountBotLimit(),
                eligibility,
                marketScope,
                scoringParameters,
                command.feePolicyId(),
                command.buyingPowerBufferPolicyId(),
                command.precisionRulesVersion(),
                rulesHash.substring("sha256:".length()),
                command.schedule(),
                clock.instant());
        commandPort.save(room, plan);
        return room;
    }

    private String canonical(Map<String, Object> document, String field) {
        if (document.isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty JSON object");
        }
        return json(new TreeMap<>(document));
    }

    private String hash(Object value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("official backtest room input is not JSON serializable", exception);
        }
    }
}
