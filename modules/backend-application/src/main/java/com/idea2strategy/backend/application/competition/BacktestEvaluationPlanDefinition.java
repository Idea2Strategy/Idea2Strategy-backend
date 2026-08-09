package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BacktestEvaluationPlanDefinition(
        String planVersion,
        String planHash,
        String commitmentHash,
        String commitmentNonceCiphertext,
        int nonceKeyVersion,
        List<Period> periods) {
    public BacktestEvaluationPlanDefinition {
        planVersion = text(planVersion, "planVersion");
        planHash = digest(planHash, "planHash");
        commitmentHash = digest(commitmentHash, "commitmentHash");
        commitmentNonceCiphertext = text(commitmentNonceCiphertext, "commitmentNonceCiphertext");
        if (nonceKeyVersion <= 0) {
            throw new IllegalArgumentException("nonceKeyVersion must be positive");
        }
        periods = List.copyOf(Objects.requireNonNull(periods, "periods"));
        if (periods.size() < 2) {
            throw new IllegalArgumentException("a backtest competition requires at least two hidden periods");
        }
        BigDecimal totalWeight = periods.stream()
                .map(Period::importanceWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("hidden period importance weights must sum to one");
        }
        for (int left = 0; left < periods.size(); left++) {
            for (int right = left + 1; right < periods.size(); right++) {
                if (periods.get(left).overlaps(periods.get(right))) {
                    throw new IllegalArgumentException("hidden backtest periods must not overlap");
                }
            }
        }
    }

    public record Period(
            UUID id,
            int sequence,
            LocalDate evaluationStart,
            LocalDate evaluationEnd,
            BigDecimal importanceWeight,
            String inputSetHash,
            List<Dataset> datasets,
            List<FeatureMaterialization> featureMaterializations) {
        public Period {
            Objects.requireNonNull(id, "id");
            if (sequence <= 0) throw new IllegalArgumentException("period sequence must be positive");
            Objects.requireNonNull(evaluationStart, "evaluationStart");
            Objects.requireNonNull(evaluationEnd, "evaluationEnd");
            if (evaluationEnd.isBefore(evaluationStart)) {
                throw new IllegalArgumentException("period end must not precede its start");
            }
            Objects.requireNonNull(importanceWeight, "importanceWeight");
            if (importanceWeight.signum() <= 0 || importanceWeight.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("period importanceWeight must be in (0, 1]");
            }
            inputSetHash = digest(inputSetHash, "inputSetHash");
            datasets = List.copyOf(Objects.requireNonNull(datasets, "datasets"));
            featureMaterializations = List.copyOf(
                    Objects.requireNonNull(featureMaterializations, "featureMaterializations"));
            if (datasets.isEmpty()) {
                throw new IllegalArgumentException("each hidden period requires official datasets");
            }
        }

        boolean overlaps(Period other) {
            return !evaluationEnd.isBefore(other.evaluationStart)
                    && !other.evaluationEnd.isBefore(evaluationStart);
        }
    }

    public record Dataset(UUID manifestId, String purposeCode, String lockedDatasetHash) {
        public Dataset {
            Objects.requireNonNull(manifestId, "manifestId");
            purposeCode = text(purposeCode, "purposeCode");
            lockedDatasetHash = digest(lockedDatasetHash, "lockedDatasetHash");
        }
    }

    public record FeatureMaterialization(UUID id, String lockedResultHash) {
        public FeatureMaterialization {
            Objects.requireNonNull(id, "id");
            lockedResultHash = digest(lockedResultHash, "lockedResultHash");
        }
    }

    private static String digest(String value, String field) {
        value = text(value, field);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a sha256 digest");
        }
        return value;
    }

    private static String text(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
