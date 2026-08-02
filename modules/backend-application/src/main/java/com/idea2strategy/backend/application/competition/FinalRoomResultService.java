package com.idea2strategy.backend.application.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class FinalRoomResultService {
    private final FinalRoomResultPort port;
    private final Clock clock;
    private final OfficialScoringCalculator calculator = new OfficialScoringCalculator();
    private final OfficialScoringRanker ranker = new OfficialScoringRanker();
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public FinalRoomResultService(FinalRoomResultPort port, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FinalRoomResultWriteDecision finalize(FinalRoomResultCommand command) {
        Objects.requireNonNull(command, "command");
        Map<UUID, FinalRoomResultCandidate> candidates = new LinkedHashMap<>();
        List<OfficialScoringResult> scoreable = new ArrayList<>();
        Map<UUID, OfficialScoringIneligibilityReason> scoringIneligibility = new LinkedHashMap<>();
        for (FinalRoomResultCandidate candidate : command.candidates().stream()
                .sorted(Comparator.comparing(FinalRoomResultCandidate::participationId)).toList()) {
            candidates.put(candidate.participationId(), candidate);
            if (candidate.eligibility().eligible()) {
                BigDecimal score;
                try {
                    score = calculator.score(command.scoringTemplate(), candidate.metrics());
                } catch (IllegalArgumentException exception) {
                    if (!exception.getMessage().startsWith("required scoring metric is unavailable:")) {
                        throw exception;
                    }
                    scoringIneligibility.put(
                            candidate.participationId(),
                            OfficialScoringIneligibilityReason.REQUIRED_METRIC_UNAVAILABLE);
                    continue;
                }
                scoreable.add(new OfficialScoringResult(
                        candidate.participationId(), score,
                        command.scoringTemplate().kind() == com.idea2strategy.backend.domain.competition.ScoringTemplateKind.SINGLE
                                ? command.scoringTemplate().components().getFirst().direction()
                                : com.idea2strategy.backend.domain.competition.ScoringDirection.HIGHER_IS_BETTER,
                        candidate.metrics()));
            }
        }

        List<OfficialScoringRank> ranked = ranker.rank(scoreable);
        Map<Integer, Long> rankCounts = ranked.stream().collect(java.util.stream.Collectors.groupingBy(
                OfficialScoringRank::rank, java.util.stream.Collectors.counting()));
        List<FinalLeaderboardEntry> entries = new ArrayList<>();
        for (OfficialScoringRank rank : ranked) {
            FinalRoomResultCandidate candidate = candidates.remove(rank.result().participationId());
            entries.add(new FinalLeaderboardEntry(
                    candidate.participationId(), candidate.performanceSnapshotId(), rank.rank(),
                    rankCounts.get(rank.rank()) > 1, rank.result().score(), "ELIGIBLE", null,
                    candidate.provenanceHash(), tieDocument(candidate.metrics()),
                    calculationDocument(candidate)));
        }
        for (FinalRoomResultCandidate candidate : candidates.values()) {
            OfficialScoringIneligibilityReason reason = scoringIneligibility.get(candidate.participationId());
            if (reason == null) {
                reason = candidate.eligibility().reasons().getFirst();
            }
            entries.add(new FinalLeaderboardEntry(
                    candidate.participationId(), candidate.performanceSnapshotId(), null, false, null,
                    "INELIGIBLE_PRIVATE", reason, candidate.provenanceHash(),
                    tieDocument(candidate.metrics()), calculationDocument(candidate)));
        }
        UUID snapshotId = UUID.nameUUIDFromBytes(
                ("final-leaderboard.v1:" + command.roomId() + ":" + command.cutoffAt())
                        .getBytes(StandardCharsets.UTF_8));
        String resultHash = hash(command, entries);
        return port.save(new FinalRoomResult(
                snapshotId, command.roomId(), command.scoringTemplateVersionId(), command.cutoffAt(),
                resultHash, clock.instant(), entries));
    }

    private String tieDocument(OfficialScoringMetrics metrics) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("totalReturnPct", metrics.totalReturnPct().toPlainString());
        values.put("sharpeRatio", metrics.sharpeRatio() == null ? null : metrics.sharpeRatio().toPlainString());
        values.put("maxDrawdownPct", metrics.maxDrawdownPct().toPlainString());
        return json(values);
    }

    private String hash(FinalRoomResultCommand command, List<FinalLeaderboardEntry> entries) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", "final-leaderboard.v1");
        document.put("roomId", command.roomId().toString());
        document.put("scoringTemplateVersionId", command.scoringTemplateVersionId().toString());
        document.put("cutoffAt", command.cutoffAt().toString());
        document.put("entries", entries);
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(document).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String calculationDocument(FinalRoomResultCandidate candidate) {
        try {
            var root = mapper.readTree(candidate.calculationDocument());
            if (!root.isObject()) {
                throw new IllegalArgumentException("calculationDocument must be a JSON object");
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) root)
                    .put("provenanceHash", candidate.provenanceHash());
            return json(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("calculationDocument must be valid JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("final result evidence is not JSON serializable", exception);
        }
    }
}
