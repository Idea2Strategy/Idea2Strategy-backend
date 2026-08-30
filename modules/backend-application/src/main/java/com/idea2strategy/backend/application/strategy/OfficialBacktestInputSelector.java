package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.Dataset;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.ExecutionPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Selects the complete immutable official input set; callers never choose catalog identifiers. */
public final class OfficialBacktestInputSelector {
    private static final ObjectMapper JSON = new ObjectMapper();

    private OfficialBacktestInputSelector() {}

    public static Selection select(String compiledPlanDocument, StrategyReleaseInputCatalog catalog) {
        ExecutionPolicy policy = selectPolicy(catalog);
        Requirements requirements = requirements(compiledPlanDocument);

        return selectDatasets(
                policy, requirements, policy.periodStart(), policy.periodEnd().minusDays(1), catalog);
    }

    public static Selection select(
            String compiledPlanDocument,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            StrategyReleaseInputCatalog catalog) {
        if (requestedStart == null || requestedEnd == null || requestedStart.isAfter(requestedEnd)) {
            throw new IllegalArgumentException("A valid requested backtest period is required");
        }
        ExecutionPolicy policy = selectPolicy(catalog);
        Requirements requirements = requirements(compiledPlanDocument);

        return selectDatasets(policy, requirements, requestedStart, requestedEnd, catalog);
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
            Requirements requirements,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            StrategyReleaseInputCatalog catalog) {
        if (requestedStart.isBefore(policy.periodStart())
                || requestedEnd.isAfter(policy.periodEnd().minusDays(1))) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Requested official backtest period is outside the locked execution policy");
        }
        List<Dataset> candidates = catalog.datasets().stream()
                .filter(dataset -> "ADJUSTED".equals(dataset.dataLayer()))
                .filter(dataset -> policy.marketDataSchemaVersion().equals(dataset.schemaVersion()))
                .filter(dataset -> dataset.periodEnd().isAfter(policy.periodStart()))
                .filter(dataset -> dataset.periodStart().isBefore(policy.periodEnd()))
                .filter(dataset -> !dataset.availableAt().isAfter(catalog.observedAt()))
                .filter(dataset -> requirements.resolutions().contains(normalizeResolution(dataset.resolution())))
                .toList();

        Set<Dataset> selected = new LinkedHashSet<>();
        for (String resolution : requirements.resolutions().stream()
                .sorted(Comparator.comparing(OfficialBacktestInputSelector::duration))
                .toList()) {
            long warmupDays = requirements.warmupDays().getOrDefault(resolution, 1L);
            LocalDate coverageStart = requestedStart.minusDays(warmupDays);
            if (coverageStart.isBefore(policy.periodStart())) coverageStart = policy.periodStart();
            LocalDate coverageEnd = requestedEnd.plusDays(1);
            Set<UUID> instruments = requirements.instruments().getOrDefault(resolution, Set.of());
            List<UUID> scopes = instruments.isEmpty() ? java.util.Arrays.asList((UUID) null) : instruments.stream().sorted().toList();
            for (UUID instrument : scopes) {
                UUID requiredInstrument = instrument;
                List<Dataset> eligible = candidates.stream()
                        .filter(dataset -> resolution.equals(normalizeResolution(dataset.resolution())))
                        .filter(dataset -> dataset.instrumentId() == null
                                || dataset.instrumentId().equals(requiredInstrument))
                        .toList();
                if (requiredInstrument != null) {
                    List<Dataset> scoped = eligible.stream()
                            .filter(dataset -> requiredInstrument.equals(dataset.instrumentId()))
                            .toList();
                    Cover scopedCover = coverFrom(coverageStart, coverageStart, coverageEnd, scoped);
                    if (scopedCover != null) {
                        selected.addAll(scopedCover.datasets());
                        continue;
                    }
                }
                selected.addAll(minimumCover(resolution, coverageStart, coverageEnd, eligible));
            }
        }
        return new Selection(policy, List.copyOf(selected));
    }

    private static List<Dataset> minimumCover(
            String resolution, LocalDate requestedStart, LocalDate requestedEnd, List<Dataset> candidates) {
        Cover best = coverFrom(requestedStart, requestedStart, requestedEnd, candidates);
        if (best == null) {
            LocalDate firstGap = firstGap(requestedStart, requestedEnd, candidates);
            throw new ImmutableStrategyReleaseRejectedException(
                    "No official " + resolution + " dataset manifest covers " + firstGap
                            + " within requested period " + requestedStart + ".." + requestedEnd);
        }
        return best.datasets();
    }

    private static Cover coverFrom(
            LocalDate coverageStart,
            LocalDate cursor,
            LocalDate requestedEnd,
            List<Dataset> candidates) {
        Cover best = null;
        for (Dataset candidate : candidates.stream()
                .filter(dataset -> cursor.equals(coverageStart)
                        ? !dataset.periodStart().isAfter(cursor)
                        : dataset.periodStart().equals(cursor))
                .filter(dataset -> dataset.periodEnd().isAfter(cursor))
                .sorted(Comparator.comparing(Dataset::periodEnd).reversed()
                        .thenComparing(Comparator.comparingInt(Dataset::revisionNumber).reversed())
                        .thenComparing(Dataset::availableAt, Comparator.reverseOrder())
                        .thenComparing(dataset -> dataset.id().toString()))
                .toList()) {
            Cover path;
            if (!candidate.periodEnd().isBefore(requestedEnd)) {
                path = Cover.of(coverageStart, requestedEnd, candidate);
            } else {
                Cover suffix = coverFrom(coverageStart, candidate.periodEnd(), requestedEnd, candidates);
                if (suffix == null || suffix.datasets().contains(candidate)) {
                    continue;
                }
                List<Dataset> datasets = new ArrayList<>();
                datasets.add(candidate);
                datasets.addAll(suffix.datasets());
                path = Cover.of(coverageStart, requestedEnd, datasets);
            }
            if (best == null || path.compareTo(best) < 0) {
                best = path;
            }
        }
        return best;
    }

    private static LocalDate firstGap(
            LocalDate requestedStart, LocalDate requestedEnd, List<Dataset> candidates) {
        LocalDate cursor = requestedStart;
        while (cursor.isBefore(requestedEnd)) {
            LocalDate current = cursor;
            LocalDate next = candidates.stream()
                    .filter(dataset -> !dataset.periodStart().isAfter(current))
                    .filter(dataset -> dataset.periodEnd().isAfter(current))
                    .map(Dataset::periodEnd)
                    .max(LocalDate::compareTo)
                    .orElse(current);
            if (next.equals(cursor)) return cursor;
            cursor = next;
        }
        return cursor;
    }

    private static Requirements requirements(String compiledPlanDocument) {
        final JsonNode plan;
        try {
            plan = JSON.readTree(compiledPlanDocument);
        } catch (JsonProcessingException exception) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Compiled plan is not readable while selecting official backtest inputs");
        }
        Set<String> resolutions = new LinkedHashSet<>();
        Map<String, Set<UUID>> instruments = new HashMap<>();
        Map<String, Long> warmupDays = new HashMap<>();
        plan.path("requiredFeatures").forEach(feature -> {
            String resolution = resolution(feature.path("resolution"));
            if (resolution == null) return;
            resolutions.add(resolution);
            feature.path("instruments").forEach(value -> addInstrument(instruments, resolution, value));
            long observations = Math.max(1, feature.path("requiredObservations").asLong(
                    feature.path("requiredHistoryPoints").asLong(1)));
            warmupDays.merge(resolution, warmupDays(resolution, observations), Math::max);
        });
        plan.path("executionSnapshot").path("partitions").forEach(partition ->
                partition.path("flows").forEach(flow -> addFlowRequirements(flow, resolutions, instruments, warmupDays)));
        plan.path("flows").forEach(flow -> addFlowRequirements(flow, resolutions, instruments, warmupDays));
        if (resolutions.isEmpty()) {
            throw new ImmutableStrategyReleaseRejectedException(
                    "Compiled plan declares no market-data resolution");
        }
        return new Requirements(Set.copyOf(resolutions), Map.copyOf(instruments), Map.copyOf(warmupDays));
    }

    private static void addFlowRequirements(
            JsonNode flow, Set<String> resolutions, Map<String, Set<UUID>> instruments, Map<String, Long> warmupDays) {
        Set<UUID> flowInstruments = new LinkedHashSet<>();
        JsonNode instrumentValues = flow.has("officialInstrumentIds")
                ? flow.path("officialInstrumentIds") : flow.path("instrumentIds");
        instrumentValues.forEach(value -> {
            if (value.isTextual()) flowInstruments.add(UUID.fromString(value.textValue()));
        });
        flow.path("steps").forEach(step -> {
            JsonNode value = step.path("arguments").path("resolution");
            if (!value.isTextual()) value = step.path("parameters").path("resolution");
            String resolution = resolution(value);
            if (resolution == null) return;
            resolutions.add(resolution);
            instruments.computeIfAbsent(resolution, ignored -> new LinkedHashSet<>()).addAll(flowInstruments);
            warmupDays.merge(resolution, warmupDays(resolution, 1), Math::max);
        });
    }

    private static void addInstrument(Map<String, Set<UUID>> target, String resolution, JsonNode value) {
        if (value.isTextual()) {
            target.computeIfAbsent(resolution, ignored -> new LinkedHashSet<>()).add(UUID.fromString(value.textValue()));
        }
    }

    private static String resolution(JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank() ? normalizeResolution(value.textValue()) : null;
    }

    private static long warmupDays(String resolution, long observations) {
        long seconds = Math.multiplyExact(duration(resolution).toSeconds(), observations);
        return Math.max(1, (seconds + Duration.ofDays(1).toSeconds() - 1) / Duration.ofDays(1).toSeconds());
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

    private record Cover(
            List<Dataset> datasets,
            List<Integer> revisionVector,
            List<Instant> availabilityVector,
            long outsideDays)
            implements Comparable<Cover> {
        private Cover {
            datasets = List.copyOf(datasets);
            revisionVector = List.copyOf(revisionVector);
            availabilityVector = List.copyOf(availabilityVector);
        }

        static Cover of(LocalDate requestedStart, LocalDate requestedEnd, Dataset dataset) {
            return of(requestedStart, requestedEnd, List.of(dataset));
        }

        static Cover of(LocalDate requestedStart, LocalDate requestedEnd, List<Dataset> datasets) {
            List<Dataset> ordered = datasets.stream()
                    .sorted(Comparator.comparing(Dataset::periodStart)
                            .thenComparing(Dataset::periodEnd)
                            .thenComparing(dataset -> dataset.id().toString()))
                    .toList();
            List<Integer> revisions = ordered.stream()
                    .map(Dataset::revisionNumber)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            List<Instant> availability = ordered.stream()
                    .map(Dataset::availableAt)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            long outside = java.time.temporal.ChronoUnit.DAYS.between(
                    ordered.getFirst().periodStart(), requestedStart)
                    + java.time.temporal.ChronoUnit.DAYS.between(
                            requestedEnd, ordered.getLast().periodEnd());
            return new Cover(ordered, revisions, availability, outside);
        }

        @Override
        public int compareTo(Cover other) {
            int count = Integer.compare(datasets.size(), other.datasets.size());
            if (count != 0) return count;
            for (int index = 0; index < revisionVector.size(); index++) {
                int revision = Integer.compare(other.revisionVector.get(index), revisionVector.get(index));
                if (revision != 0) return revision;
            }
            for (int index = 0; index < availabilityVector.size(); index++) {
                int availability = other.availabilityVector.get(index).compareTo(availabilityVector.get(index));
                if (availability != 0) return availability;
            }
            int outside = Long.compare(outsideDays, other.outsideDays);
            if (outside != 0) return outside;
            String ids = datasets.stream().map(dataset -> dataset.id().toString())
                    .collect(java.util.stream.Collectors.joining("|"));
            String otherIds = other.datasets.stream().map(dataset -> dataset.id().toString())
                    .collect(java.util.stream.Collectors.joining("|"));
            return ids.compareTo(otherIds);
        }
    }

    private record Requirements(
            Set<String> resolutions, Map<String, Set<UUID>> instruments, Map<String, Long> warmupDays) {}
}
