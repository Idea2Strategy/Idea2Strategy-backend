package com.idea2strategy.backend.application.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Completes due live-paper evidence and publishes one immutable FINAL leaderboard per room. */
public final class RoomFinalizationService {
    private final RoomFinalizationWorkPort workPort;
    private final VirtualLiquidationService liquidationService;
    private final ScoringEvidenceService evidenceService;
    private final FinalRoomResultService resultService;
    private final ScoringTemplateCatalogService templateCatalog;
    private final OfficialScoringCalculator scoring = new OfficialScoringCalculator();
    private final Clock clock;
    private final ObjectMapper mapper;

    public RoomFinalizationService(
            RoomFinalizationWorkPort workPort,
            VirtualLiquidationService liquidationService,
            ScoringEvidenceService evidenceService,
            FinalRoomResultService resultService,
            ScoringTemplateCatalogService templateCatalog,
            Clock clock,
            ObjectMapper mapper) {
        this.workPort = Objects.requireNonNull(workPort, "workPort");
        this.liquidationService = Objects.requireNonNull(liquidationService, "liquidationService");
        this.evidenceService = Objects.requireNonNull(evidenceService, "evidenceService");
        this.resultService = Objects.requireNonNull(resultService, "resultService");
        this.templateCatalog = Objects.requireNonNull(templateCatalog, "templateCatalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public RoomFinalizationReport run(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        var observedAt = clock.instant();
        List<UUID> roomIds = workPort.findDueRoomIds(observedAt, limit);
        List<RoomFinalizationFailure> failures = new ArrayList<>();
        int roomsFinalized = 0;
        int participationsFinalized = 0;
        for (UUID roomId : roomIds) {
            try {
                for (VirtualLiquidationRequest request : workPort.findPendingLiquidations(roomId)) {
                    liquidationService.finalizeEvaluation(request);
                    workPort.markEvaluationCompleted(request, observedAt);
                    participationsFinalized++;
                }
                var ready = workPort.loadReadyResult(roomId);
                if (ready.isEmpty()) {
                    continue;
                }
                resultService.finalize(command(ready.orElseThrow()));
                roomsFinalized++;
            } catch (RuntimeException exception) {
                String message = exception.getMessage();
                failures.add(new RoomFinalizationFailure(
                        roomId,
                        exception.getClass().getSimpleName() + (message == null ? "" : ": " + message)));
            }
        }
        return new RoomFinalizationReport(
                observedAt, roomIds.size(), roomsFinalized, participationsFinalized, failures);
    }

    private FinalRoomResultCommand command(RoomFinalizationSource source) {
        var template = templateCatalog.parseLocked(source.scoringTemplate());
        List<FinalRoomResultCandidate> candidates = source.candidates().stream()
                .map(candidate -> candidate(source, candidate))
                .toList();
        return new FinalRoomResultCommand(
                source.roomId(), template.id(), source.cutoffAt(), template, candidates);
    }

    private FinalRoomResultCandidate candidate(
            RoomFinalizationSource room, RoomFinalizationCandidateSource candidate) {
        var evidence = evidenceService.prepare(new ScoringEvidenceRequest(
                candidate.participationId(),
                candidate.evaluationSegmentId(),
                candidate.performanceSnapshotId(),
                room.scoringTemplate().id()));
        var source = evidence.source();
        var eligibility = scoring.eligibility(
                candidate.scheduledEvaluationSeconds(),
                candidate.scheduledEvaluationSeconds(),
                candidate.actualOperationSeconds(),
                candidate.baseRequiredOperationSeconds(),
                candidate.actualFillCount(),
                candidate.baseRequiredFillCount(),
                true);
        return new FinalRoomResultCandidate(
                candidate.participationId(),
                candidate.performanceSnapshotId(),
                new OfficialScoringMetrics(
                        source.totalReturnPct(), source.maxDrawdownPct(), source.sharpeRatio()),
                eligibility,
                evidence.provenanceHash(),
                calculationDocument(evidence, candidate, eligibility));
    }

    private String calculationDocument(
            ScoringEvidenceBundle evidence,
            RoomFinalizationCandidateSource candidate,
            OfficialScoringEligibility eligibility) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", "live-room-finalization.v1");
        document.put("provenanceVersion", evidence.provenanceVersion());
        document.put("performanceSnapshotHash", evidence.source().performanceSnapshotHash());
        document.put("roomRulesHash", evidence.source().roomRulesHash());
        document.put("scoringTemplateRulesHash", evidence.source().lockedScoringTemplateRulesHash());
        document.put("scheduledEvaluationSeconds", candidate.scheduledEvaluationSeconds());
        document.put("normalEvaluationSeconds", candidate.scheduledEvaluationSeconds());
        document.put("actualOperationSeconds", candidate.actualOperationSeconds());
        document.put("actualFillCount", candidate.actualFillCount());
        document.put("requiredOperationSeconds", eligibility.requiredOperationSeconds());
        document.put("requiredFillCount", eligibility.requiredFillCount());
        document.put("coverage", eligibility.coverage().toPlainString());
        document.put("eligibilityReasons", eligibility.reasons().stream().map(Enum::name).toList());
        try {
            return mapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("room finalization evidence is not JSON serializable", exception);
        }
    }
}
