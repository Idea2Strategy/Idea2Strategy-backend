package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.BacktestEvaluationPlanDefinition;
import com.idea2strategy.backend.application.competition.CreateOfficialBacktestRoomCommand;
import com.idea2strategy.backend.application.competition.OfficialBacktestCompetitionRoomCreationService;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import com.idea2strategy.backend.domain.competition.RoomStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operator-only control plane for hidden, server-locked BACKTEST competitions. */
@RestController
@RequestMapping("/api/v1/operations/competition/backtest-rooms")
@ConditionalOnBean(OfficialBacktestCompetitionRoomCreationService.class)
public class OfficialBacktestCompetitionRoomController {
    private final OfficialBacktestCompetitionRoomCreationService service;

    public OfficialBacktestCompetitionRoomController(
            OfficialBacktestCompetitionRoomCreationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Response create(@RequestBody Request request) {
        var room = service.create(request.toCommand());
        return new Response(room.id(), room.accessType(), room.status(), room.lockedAt());
    }

    public record Request(
            String name,
            RoomAccessType accessType,
            UUID scoringTemplateVersionId,
            BigDecimal initialCashAmount,
            int botParticipationLimit,
            int perAccountBotLimit,
            UUID feePolicyId,
            UUID buyingPowerBufferPolicyId,
            Map<String, Object> eligibilityCriteria,
            Map<String, Object> marketScope,
            String precisionRulesVersion,
            Instant recruitmentOpensAt,
            Instant participationOpensAt,
            Instant evaluationStartsAt,
            Instant participationClosesAt,
            Instant evaluationEndsAt,
            Instant finalizationDeadlineAt,
            String timezoneName,
            String planVersion,
            String planHash,
            String commitmentHash,
            String commitmentNonceCiphertext,
            int nonceKeyVersion,
            List<Period> periods) {
        CreateOfficialBacktestRoomCommand toCommand() {
            return new CreateOfficialBacktestRoomCommand(
                    name,
                    accessType,
                    scoringTemplateVersionId,
                    initialCashAmount,
                    botParticipationLimit,
                    perAccountBotLimit,
                    feePolicyId,
                    buyingPowerBufferPolicyId,
                    eligibilityCriteria,
                    marketScope,
                    precisionRulesVersion,
                    new RoomSchedule(
                            recruitmentOpensAt,
                            participationOpensAt,
                            evaluationStartsAt,
                            participationClosesAt,
                            evaluationEndsAt,
                            finalizationDeadlineAt,
                            timezoneName),
                    planVersion,
                    planHash,
                    commitmentHash,
                    commitmentNonceCiphertext,
                    nonceKeyVersion,
                    periods.stream().map(Period::toCommand).toList());
        }
    }

    public record Period(
            LocalDate evaluationStart,
            LocalDate evaluationEnd,
            BigDecimal importanceWeight,
            String inputSetHash,
            List<Dataset> datasets,
            List<FeatureMaterialization> featureMaterializations) {
        CreateOfficialBacktestRoomCommand.Period toCommand() {
            return new CreateOfficialBacktestRoomCommand.Period(
                    evaluationStart,
                    evaluationEnd,
                    importanceWeight,
                    inputSetHash,
                    datasets.stream().map(Dataset::toDefinition).toList(),
                    featureMaterializations.stream().map(FeatureMaterialization::toDefinition).toList());
        }
    }

    public record Dataset(UUID manifestId, String purposeCode, String lockedDatasetHash) {
        BacktestEvaluationPlanDefinition.Dataset toDefinition() {
            return new BacktestEvaluationPlanDefinition.Dataset(
                    manifestId, purposeCode, lockedDatasetHash);
        }
    }

    public record FeatureMaterialization(UUID id, String lockedResultHash) {
        BacktestEvaluationPlanDefinition.FeatureMaterialization toDefinition() {
            return new BacktestEvaluationPlanDefinition.FeatureMaterialization(id, lockedResultHash);
        }
    }

    public record Response(UUID id, RoomAccessType accessType, RoomStatus status, Instant lockedAt) {}
}
