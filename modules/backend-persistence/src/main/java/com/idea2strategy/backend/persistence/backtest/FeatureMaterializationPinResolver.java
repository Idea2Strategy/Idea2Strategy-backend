package com.idea2strategy.backend.persistence.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.persistence.backtest.BacktestRunInputPinWriter.FeaturePin;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;

/** Resolves immutable feature outputs at the producer boundary before a backtest can be published. */
@Component
public final class FeatureMaterializationPinResolver {
    static final String OUTPUT_SCHEMA = "feature-series.parquet.v1";
    private static final Pattern SHORTHAND = Pattern.compile("(?<amount>[1-9][0-9]*)(?<unit>[smhd])");
    private static final Pattern SHA_256 = Pattern.compile("(?:sha256:)?[0-9a-f]{64}");

    private final DSLContext dsl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FeatureMaterializationPinResolver(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    public List<FeaturePin> resolve(
            String compiledPlanDocument,
            LocalDate evaluationStart,
            LocalDate evaluationEnd,
            OffsetDateTime asOf) {
        Objects.requireNonNull(evaluationStart, "evaluationStart");
        Objects.requireNonNull(evaluationEnd, "evaluationEnd");
        Objects.requireNonNull(asOf, "asOf");
        if (evaluationStart.isAfter(evaluationEnd)) {
            throw new IllegalArgumentException("evaluation period is inverted");
        }

        List<Requirement> requirements = requirements(compiledPlanDocument);
        List<FeaturePin> pins = new ArrayList<>();
        var tupleKeys = new HashSet<String>();
        for (var requirement : requirements) {
            for (UUID instrumentId : requirement.instrumentIds()) {
                String tupleKey = requirement.featureId() + "|" + instrumentId;
                if (!tupleKeys.add(tupleKey)) {
                    throw new IllegalStateException("Compiled plan contains a duplicate feature/instrument requirement");
                }
                pins.add(resolveOne(requirement, instrumentId, evaluationStart, evaluationEnd, asOf));
            }
        }
        return pins.stream()
                .sorted(Comparator.comparing(pin -> pin.featureMaterializationId().toString()))
                .toList();
    }

    private FeaturePin resolveOne(
            Requirement requirement,
            UUID instrumentId,
            LocalDate evaluationStart,
            LocalDate evaluationEnd,
            OffsetDateTime asOf) {
        Duration resolution = requirement.resolution();
        OffsetDateTime requiredStart = evaluationStart.atStartOfDay().atOffset(ZoneOffset.UTC)
                .minus(resolution.multipliedBy(requirement.requiredObservations()));
        OffsetDateTime requiredEnd = evaluationEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        var candidates = dsl.fetch(
                "select fm.id, fm.result_hash, fm.period_start, fm.period_end, fm.available_at, "
                        + "fd.calculator_version, fd.resolution as definition_resolution, "
                        + "fd.required_history_points, dm.instrument_id as manifest_instrument_id, "
                        + "dm.data_layer, dm.resolution as manifest_resolution, dm.status::text as manifest_status, "
                        + "dm.period_start as manifest_period_start, dm.period_end as manifest_period_end, "
                        + "dm.schema_version, dm.dataset_hash, dm.available_at as manifest_available_at, "
                        + "(select count(*) from market_data.dataset_objects dox "
                        + " join storage.objects o on o.id = dox.object_id "
                        + " where dox.dataset_manifest_id = dm.id) as object_count, "
                        + "(select count(*) from market_data.dataset_objects dox "
                        + " join storage.objects o on o.id = dox.object_id "
                        + " where dox.dataset_manifest_id = dm.id and (o.status <> 'AVAILABLE' "
                        + " or o.provider_version_id = '' or o.schema_version <> ? or o.file_format <> 'PARQUET' "
                        + " or o.content_hash !~ '^(sha256:)?[0-9a-f]{64}$')) as invalid_object_count "
                        + "from market_data.feature_materializations fm "
                        + "join market_data.feature_definitions fd on fd.id = fm.feature_definition_id "
                        + "join market_data.dataset_manifests dm on dm.id = fm.output_dataset_manifest_id "
                        + "where fm.feature_definition_id = ? and fm.instrument_id = ? and fm.status = 'SUCCEEDED' "
                        + "and fm.period_start <= ?::timestamptz and fm.period_end >= ?::timestamptz "
                        + "and fm.available_at <= ?::timestamptz "
                        + "order by fm.id",
                OUTPUT_SCHEMA, requirement.featureId(), instrumentId, requiredStart, requiredEnd, asOf);
        if (candidates.size() != 1) {
            throw new IllegalStateException("Required feature/instrument tuple must resolve to exactly one "
                    + "SUCCEEDED feature materialization: " + requirement.featureId() + "/" + instrumentId);
        }

        Record candidate = candidates.get(0);
        if (!semanticVersion(candidate.get("calculator_version", String.class)).equals(requirement.featureVersion())) {
            throw mismatch("feature definition version");
        }
        if (!normalizedDuration(candidate.get("definition_resolution", String.class)).equals(resolution)) {
            throw mismatch("feature definition resolution");
        }
        Integer historyPoints = candidate.get("required_history_points", Integer.class);
        if (historyPoints == null || historyPoints - 1 != requirement.requiredObservations()) {
            throw mismatch("feature warm-up requirement");
        }
        requireCoverage(candidate, "period_start", "period_end", requiredStart, requiredEnd, "materialization");
        requireVisible(candidate.get("available_at", OffsetDateTime.class), asOf, "materialization");
        requireHash(candidate.get("result_hash", String.class), "materialization result hash");

        if (!instrumentId.equals(candidate.get("manifest_instrument_id", UUID.class))) {
            throw mismatch("output manifest instrument");
        }
        if (!"DERIVED".equals(candidate.get("data_layer", String.class))) {
            throw mismatch("output manifest data layer");
        }
        if (!"AVAILABLE".equals(candidate.get("manifest_status", String.class))) {
            throw mismatch("output manifest status");
        }
        if (!OUTPUT_SCHEMA.equals(candidate.get("schema_version", String.class))) {
            throw new IllegalStateException("Feature output manifest must use " + OUTPUT_SCHEMA);
        }
        if (!normalizedDuration(candidate.get("manifest_resolution", String.class)).equals(resolution)) {
            throw mismatch("output manifest resolution");
        }
        requireCoverage(
                candidate, "manifest_period_start", "manifest_period_end", requiredStart, requiredEnd, "manifest");
        requireVisible(candidate.get("manifest_available_at", OffsetDateTime.class), asOf, "manifest");
        requireHash(candidate.get("dataset_hash", String.class), "output manifest hash");
        Number objectCount = candidate.get("object_count", Number.class);
        Number invalidObjectCount = candidate.get("invalid_object_count", Number.class);
        if (objectCount == null || objectCount.longValue() == 0
                || invalidObjectCount == null || invalidObjectCount.longValue() != 0) {
            throw new IllegalStateException(
                    "Feature output manifest must identify complete versioned " + OUTPUT_SCHEMA + " objects");
        }
        return new FeaturePin(candidate.get("id", UUID.class), prefixed(candidate.get("result_hash", String.class)));
    }

    private static void requireCoverage(
            Record candidate,
            String startField,
            String endField,
            OffsetDateTime requiredStart,
            OffsetDateTime requiredEnd,
            String subject) {
        OffsetDateTime actualStart = candidate.get(startField, OffsetDateTime.class);
        OffsetDateTime actualEnd = candidate.get(endField, OffsetDateTime.class);
        if (actualStart == null || actualEnd == null
                || actualStart.isAfter(requiredStart) || actualEnd.isBefore(requiredEnd)) {
            throw mismatch(subject + " period/warm-up coverage");
        }
    }

    private static void requireVisible(OffsetDateTime availableAt, OffsetDateTime asOf, String subject) {
        if (availableAt == null || availableAt.isAfter(asOf)) {
            throw mismatch(subject + " as-of visibility");
        }
    }

    private List<Requirement> requirements(String document) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Compiled plan is not valid JSON", exception);
        }
        JsonNode required = root.path("requiredFeatures");
        if (!required.isArray() || required.isEmpty()) {
            throw new IllegalStateException("Compiled plan must declare requiredFeatures");
        }
        List<Requirement> result = new ArrayList<>();
        required.forEach(node -> {
            UUID featureId = uuid(node, "featureId");
            String featureVersion = text(node, "featureVersion");
            Duration resolution = normalizedDuration(text(node, "resolution"));
            int observations = node.path("requiredObservations").asInt(-1);
            if (observations < 0) {
                throw new IllegalStateException("Compiled feature requiredObservations must be non-negative");
            }
            JsonNode instruments = node.path("instruments");
            if (!instruments.isArray() || instruments.isEmpty()) {
                throw new IllegalStateException("Compiled feature must name at least one instrument");
            }
            List<UUID> instrumentIds = new ArrayList<>();
            instruments.forEach(item -> instrumentIds.add(UUID.fromString(item.asText())));
            if (new HashSet<>(instrumentIds).size() != instrumentIds.size()) {
                throw new IllegalStateException("Compiled feature contains duplicate instruments");
            }
            result.add(new Requirement(featureId, featureVersion, resolution, observations, List.copyOf(instrumentIds)));
        });
        return List.copyOf(result);
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(text(node, field));
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("Compiled feature " + field + " must not be blank");
        }
        return value;
    }

    private static Duration normalizedDuration(String value) {
        String trimmed = value.trim();
        var shorthand = SHORTHAND.matcher(trimmed.toLowerCase(Locale.ROOT));
        if (shorthand.matches()) {
            long amount = Long.parseLong(shorthand.group("amount"));
            return switch (shorthand.group("unit")) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        }
        try {
            return Duration.parse(trimmed);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalStateException("Feature resolution is not a duration: " + value, exception);
        }
    }

    private static String semanticVersion(String calculatorVersion) {
        int separator = calculatorVersion.lastIndexOf(':');
        return separator < 0 ? calculatorVersion : calculatorVersion.substring(separator + 1);
    }

    private static void requireHash(String value, String subject) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw mismatch(subject);
        }
    }

    private static String prefixed(String value) {
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }

    private static IllegalStateException mismatch(String subject) {
        return new IllegalStateException("Pinned feature " + subject + " does not match the compiled plan");
    }

    private record Requirement(
            UUID featureId,
            String featureVersion,
            Duration resolution,
            int requiredObservations,
            List<UUID> instrumentIds) {}
}
