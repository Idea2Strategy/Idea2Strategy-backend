package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.Dataset;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.ExecutionPolicy;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects the complete immutable official input set; callers never choose catalog identifiers. */
public final class OfficialBacktestInputSelector {
    private static final ObjectMapper JSON = new ObjectMapper();

    private OfficialBacktestInputSelector() {}

    public static Selection select(String compiledPlanDocument, StrategyReleaseInputCatalog catalog) {
        ExecutionPolicy policy = selectPolicy(catalog);
        Set<String> requiredResolutions = requiredResolutions(compiledPlanDocument);

        return selectDatasets(policy, requiredResolutions, catalog);
    }

    public static ExecutionPolicy selectPolicy(StrategyReleaseInputCatalog catalog) {
        if (catalog.executionPolicies().isEmpty()) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "No locked official backtest execution policy is available");
        }
        return catalog.executionPolicies().stream()
                .sorted(Comparator.comparing(ExecutionPolicy::lockedAt).reversed()
                        .thenComparing(ExecutionPolicy::version))
                .findFirst()
                .orElseThrow();
    }

    private static Selection selectDatasets(
            ExecutionPolicy policy,
            Set<String> requiredResolutions,
            StrategyReleaseInputCatalog catalog) {
        Map<Period, List<Dataset>> byPeriod = new HashMap<>();
        catalog.datasets().stream()
                .filter(dataset -> "ADJUSTED".equals(dataset.dataLayer()))
                .filter(dataset -> policy.marketDataSchemaVersion().equals(dataset.schemaVersion()))
                .filter(dataset -> !dataset.periodStart().isBefore(policy.periodStart()))
                .filter(dataset -> !dataset.periodEnd().isAfter(policy.periodEnd()))
                .filter(dataset -> !dataset.availableAt().isAfter(catalog.observedAt()))
                .filter(dataset -> requiredResolutions.contains(normalizeResolution(dataset.resolution())))
                .forEach(dataset -> byPeriod
                        .computeIfAbsent(new Period(dataset.periodStart(), dataset.periodEnd()), ignored -> new ArrayList<>())
                        .add(dataset));

        Period selectedPeriod = byPeriod.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .map(dataset -> normalizeResolution(dataset.resolution()))
                        .collect(java.util.stream.Collectors.toSet())
                        .containsAll(requiredResolutions))
                .map(Map.Entry::getKey)
                .sorted(Comparator
                        .comparingInt((Period period) -> period.start().equals(policy.periodStart())
                                && period.end().equals(policy.periodEnd()) ? 0 : 1)
                        .thenComparing(Period::end, Comparator.reverseOrder())
                        .thenComparing(Period::start))
                .findFirst()
                .orElseThrow(() -> new ImmutableStrategyReleaseRejectedException(
                        "No coherent official backtest dataset set covers required resolutions "
                                + String.join(", ", requiredResolutions.stream()
                                        .sorted(Comparator.comparing(OfficialBacktestInputSelector::duration))
                                        .toList())));

        List<Dataset> periodDatasets = byPeriod.get(selectedPeriod);
        List<Dataset> selected = requiredResolutions.stream()
                .sorted(Comparator.comparing(OfficialBacktestInputSelector::duration))
                .map(resolution -> periodDatasets.stream()
                        .filter(dataset -> resolution.equals(normalizeResolution(dataset.resolution())))
                        .max(Comparator.comparing(Dataset::availableAt)
                                .thenComparing(dataset -> dataset.id().toString()))
                        .orElseThrow())
                .toList();
        return new Selection(policy, selected);
    }

    private static Set<String> requiredResolutions(String compiledPlanDocument) {
        final JsonNode plan;
        try {
            plan = JSON.readTree(compiledPlanDocument);
        } catch (JsonProcessingException exception) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Compiled plan is not readable while selecting official backtest inputs");
        }
        Set<String> resolutions = new LinkedHashSet<>();
        plan.path("requiredFeatures").forEach(feature -> addResolution(resolutions, feature.path("resolution")));
        plan.path("executionSnapshot").path("partitions").forEach(partition ->
                partition.path("flows").forEach(flow ->
                        flow.path("steps").forEach(step ->
                                addResolution(resolutions, step.path("arguments").path("resolution")))));
        if (resolutions.isEmpty()) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Compiled plan declares no market-data resolution");
        }
        return java.util.Collections.unmodifiableSet(resolutions);
    }

    private static void addResolution(Set<String> target, JsonNode value) {
        if (value.isTextual() && !value.textValue().isBlank()) {
            target.add(normalizeResolution(value.textValue()));
        }
    }

    private static String normalizeResolution(String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (Set.of("30m", "1h", "4h", "1d").contains(normalized)) {
            return normalized;
        }
        try {
            Duration parsed = Duration.parse(value.toUpperCase(java.util.Locale.ROOT));
            if (parsed.equals(Duration.ofMinutes(30))) return "30m";
            if (parsed.equals(Duration.ofHours(1))) return "1h";
            if (parsed.equals(Duration.ofHours(4))) return "4h";
            if (parsed.equals(Duration.ofDays(1))) return "1d";
        } catch (RuntimeException ignored) {
            // The stable failure below names the unsupported contract value.
        }
        throw new ImmutableStrategyReleaseRejectedException(
                "Unsupported official backtest resolution " + value);
    }

    private static Duration duration(String resolution) {
        return switch (resolution) {
            case "30m" -> Duration.ofMinutes(30);
            case "1h" -> Duration.ofHours(1);
            case "4h" -> Duration.ofHours(4);
            case "1d" -> Duration.ofDays(1);
            default -> throw new IllegalArgumentException("Unsupported resolution " + resolution);
        };
    }

    public record Selection(ExecutionPolicy policy, List<Dataset> datasets) {
        public Selection {
            datasets = List.copyOf(datasets);
        }
    }

    private record Period(LocalDate start, LocalDate end) {}
}
