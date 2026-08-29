package com.idea2strategy.backend.persistence.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.persistence.backtest.BacktestRunInputPinWriter.FeaturePin;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private static final UUID UUID_NAMESPACE = UUID.fromString("05a27d5a-75d8-4d57-bc9a-31cedf90d791");
    private static final String INTERNAL_PROVIDER_CODE = "IDEA2STRATEGY_INTERNAL";
    private static final String INTERNAL_PROVIDER_RIGHTS = "internal-derived-v1";
    private static final String FEATURE_PIPELINE_CODE = "MATERIALIZE_FEATURE_OUTPUT";
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
        OffsetDateTime requiredStart = evaluationStart.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime requiredEnd = evaluationEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        var candidates = dsl.fetch(
                "select fm.id, fm.input_dataset_set_hash, fm.result_hash, fm.period_start, fm.period_end, "
                        + "fm.available_at, "
                        + "pr.pipeline_code, pr.pipeline_version, pr.input_hash as pipeline_input_hash, "
                        + "pr.status::text as pipeline_status, pr.output_hash as pipeline_output_hash, "
                        + "pr.completed_at as pipeline_completed_at, "
                        + "fd.feature_code, fd.calculator_version, fd.resolution as definition_resolution, "
                        + "fd.definition_hash, fd.required_history_points, provider.code as provider_code, "
                        + "provider.rights_version as provider_rights_version, "
                        + "provider.status as provider_status, feed.data_kind as feed_data_kind, "
                        + "feed.resolution as feed_resolution, feed.timezone_name as feed_timezone_name, "
                        + "feed.id as feed_id, feed.code as feed_code, feed.feed_version, "
                        + "feed.retired_at as feed_retired_at, dm.id as manifest_id, "
                        + "dm.instrument_id as manifest_instrument_id, "
                        + "dm.data_layer, dm.resolution as manifest_resolution, dm.status::text as manifest_status, "
                        + "dm.period_start as manifest_period_start, dm.period_end as manifest_period_end, "
                        + "dm.schema_version, dm.dataset_hash, dm.available_at as manifest_available_at, "
                        + "dm.object_count as manifest_object_count, "
                        + "(select count(*) from market_data.dataset_objects dox "
                        + " join storage.objects o on o.id = dox.object_id "
                        + " where dox.dataset_manifest_id = dm.id) as object_count, "
                        + "(select count(*) from market_data.dataset_objects dox "
                        + " join storage.objects o on o.id = dox.object_id "
                        + " where dox.dataset_manifest_id = dm.id and (o.status <> 'AVAILABLE' "
                        + " or btrim(o.provider_version_id) = '' or o.schema_version <> ? "
                        + " or o.file_format <> 'PARQUET' or dox.object_kind <> 'FEATURE_SERIES' "
                        + " or dox.row_count is null or dox.row_count <= 0 "
                        + " or o.row_count is null or o.row_count <= 0 or o.byte_size <= 0 "
                        + " or o.verified_at is null or o.verified_at > ?::timestamptz "
                        + " or o.quarantined_at is not null or o.superseded_at is not null "
                        + " or o.deleted_at is not null "
                        + " or o.content_hash !~ '^(sha256:)?[0-9a-f]{64}$')) as invalid_object_count "
                        + "from market_data.feature_materializations fm "
                        + "join market_data.feature_definitions fd on fd.id = fm.feature_definition_id "
                        + "join market_data.pipeline_runs pr on pr.id = fm.pipeline_run_id "
                        + "join market_data.dataset_manifests dm on dm.id = fm.output_dataset_manifest_id "
                        + "join market_data.feeds feed on feed.id = dm.feed_id "
                        + "join market_data.providers provider on provider.id = feed.provider_id "
                        + "where fm.feature_definition_id = ? and fm.instrument_id = ? and fm.status = 'SUCCEEDED' "
                        + "and dm.status = 'AVAILABLE' "
                        + "and fm.period_start <= ?::timestamptz and fm.period_end >= ?::timestamptz "
                        + "and fm.available_at <= ?::timestamptz "
                        + "order by fm.id",
                OUTPUT_SCHEMA, asOf, requirement.featureId(), instrumentId, requiredStart, requiredEnd, asOf);
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
        String resultHash = candidate.get("result_hash", String.class);
        requireHash(resultHash, "materialization result hash");

        String pipelineOutputHash = candidate.get("pipeline_output_hash", String.class);
        requireHash(pipelineOutputHash, "pipeline result hash");
        OffsetDateTime pipelineCompletedAt = candidate.get("pipeline_completed_at", OffsetDateTime.class);
        if (!"SUCCEEDED".equals(candidate.get("pipeline_status", String.class))
                || pipelineCompletedAt == null || pipelineCompletedAt.isAfter(asOf)
                || !prefixed(resultHash).equals(prefixed(pipelineOutputHash))) {
            throw mismatch("pipeline result hash/status");
        }
        if (!FEATURE_PIPELINE_CODE.equals(candidate.get("pipeline_code", String.class))
                || !OUTPUT_SCHEMA.equals(candidate.get("pipeline_version", String.class))
                || !Objects.equals(candidate.get("input_dataset_set_hash", String.class),
                        candidate.get("pipeline_input_hash", String.class))) {
            throw mismatch("pipeline identity/input");
        }

        String definitionHash = candidate.get("definition_hash", String.class);
        String calculatorVersion = candidate.get("calculator_version", String.class);
        String definitionResolution = candidate.get("definition_resolution", String.class);
        String featureCode = candidate.get("feature_code", String.class);
        /* Both hash spellings are canonical, because the pipeline changed convention and both
           forms are live. The legacy 1m RSI definition is stored prefixed; the production
           per-resolution definitions are stored as bare hex, which is what
           market_pipeline_lib.features.hashing enforces. The value is passed to
           deterministicUuid unchanged either way: the pipeline derived the feed id from exactly
           the bytes it stored, so normalising here would break the feed-id match instead. */
        if (definitionHash == null || !SHA_256.matcher(definitionHash).matches()) {
            throw mismatch("feature definition hash");
        }
        UUID expectedFeedId = expectedFeatureOutputFeedId(
                requirement.featureId(), definitionHash, calculatorVersion, definitionResolution);
        if (!INTERNAL_PROVIDER_CODE.equals(candidate.get("provider_code", String.class))
                || !INTERNAL_PROVIDER_RIGHTS.equals(candidate.get("provider_rights_version", String.class))
                || !"ACTIVE".equals(candidate.get("provider_status", String.class))) {
            throw mismatch("feature output feed identity: provider");
        }
        if (!expectedFeedId.equals(candidate.get("feed_id", UUID.class))) {
            throw mismatch("feature output feed identity: deterministic id");
        }
        if (!expectedFeedCode(featureCode, definitionResolution, calculatorVersion)
                .equals(candidate.get("feed_code", String.class))) {
            throw mismatch("feature output feed identity: code");
        }
        if (!expectedFeedVersion(calculatorVersion).equals(candidate.get("feed_version", String.class))) {
            throw mismatch("feature output feed identity: version");
        }
        if (!"FEATURE_SERIES".equals(candidate.get("feed_data_kind", String.class))
                || !"UTC".equals(candidate.get("feed_timezone_name", String.class))
                || !normalizedDuration(candidate.get("feed_resolution", String.class)).equals(resolution)) {
            throw mismatch("feature output feed identity: contract");
        }
        OffsetDateTime feedRetiredAt = candidate.get("feed_retired_at", OffsetDateTime.class);
        if (feedRetiredAt != null && !feedRetiredAt.isAfter(asOf)) {
            throw mismatch("feature output feed availability");
        }

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
        Number manifestObjectCount = candidate.get("manifest_object_count", Number.class);
        Number objectCount = candidate.get("object_count", Number.class);
        Number invalidObjectCount = candidate.get("invalid_object_count", Number.class);
        if (manifestObjectCount == null || manifestObjectCount.longValue() <= 0
                || objectCount == null || objectCount.longValue() != manifestObjectCount.longValue()
                || invalidObjectCount == null || invalidObjectCount.longValue() != 0) {
            throw new IllegalStateException(
                    "Feature output manifest must identify complete versioned " + OUTPUT_SCHEMA + " objects");
        }
        requireObjectCoverage(candidate.get("manifest_id", UUID.class), requiredStart, requiredEnd);
        return new FeaturePin(candidate.get("id", UUID.class), prefixed(resultHash));
    }

    static UUID expectedFeatureOutputFeedId(
            UUID featureDefinitionId,
            String definitionHash,
            String calculatorVersion,
            String definitionResolution) {
        if ("rsi:1.0.0".equals(calculatorVersion)) {
            if (featureDefinitionId.equals(UUID.fromString("ec37984b-6605-5560-8ea0-774c5b8e9626"))
                    && definitionHash.equals("sha256:250df12e46d233e7b8ece86c64df7a3941f0d70436aebe522b1387f15fb346dc")
                    && definitionResolution.equals("30m")) {
                return UUID.fromString("57794d8c-2254-53e4-966e-44f97edd9e6a");
            }
            if (featureDefinitionId.equals(UUID.fromString("85f4f80f-be4e-d9dc-bd52-d4781ba5f30f"))
                    && definitionHash.equals("sha256:7e8c5600ff2bf07a043f797a50d6467f86fbdb56ee532c87929df97f246af2de")
                    && definitionResolution.equals("1h")) {
                return UUID.fromString("28012549-4f45-56d3-8bb6-329e4c7a9d77");
            }
            if (featureDefinitionId.equals(UUID.fromString("65a5aaf5-f536-820f-119a-239b0aec0de7"))
                    && definitionHash.equals("sha256:42e28b02a1552eb2aa42e0d89b1ea3dd909ee8d34c3bc290c4ce0234c6d705da")
                    && definitionResolution.equals("4h")) {
                return UUID.fromString("e1d7d508-aaf1-5ae9-8098-c4af870f6fa4");
            }
            if (featureDefinitionId.equals(UUID.fromString("647a5fd6-98ed-0617-d4b2-844748d54fac"))
                    && definitionHash.equals("sha256:64dbbcda7352d0add9a4a6a6ed94a780603880891684dc32cf39e0a3d1167422")
                    && definitionResolution.equals("1d")) {
                return UUID.fromString("6d2647f8-5caf-55ee-8821-869dc693f68a");
            }
        }
        return deterministicUuid(
                "feature-output-feed", definitionHash, calculatorVersion, definitionResolution, OUTPUT_SCHEMA);
    }

    private void requireObjectCoverage(UUID manifestId, OffsetDateTime requiredStart, OffsetDateTime requiredEnd) {
        var receipts = dsl.fetch(
                "select dox.period_start as membership_period_start, dox.period_end as membership_period_end, "
                        + "dox.row_count as membership_row_count, o.period_start as object_period_start, "
                        + "o.period_end as object_period_end, o.row_count as object_row_count "
                        + "from market_data.dataset_objects dox "
                        + "join storage.objects o on o.id = dox.object_id "
                        + "where dox.dataset_manifest_id = ? "
                        + "order by dox.period_start, dox.period_end, dox.shard_key, dox.part_number, dox.id",
                manifestId);
        OffsetDateTime latestEnd = null;
        OffsetDateTime previousStart = null;
        for (Record receipt : receipts) {
            OffsetDateTime membershipStart = receipt.get("membership_period_start", OffsetDateTime.class);
            OffsetDateTime membershipEnd = receipt.get("membership_period_end", OffsetDateTime.class);
            OffsetDateTime objectStart = receipt.get("object_period_start", OffsetDateTime.class);
            OffsetDateTime objectEnd = receipt.get("object_period_end", OffsetDateTime.class);
            Number membershipRows = receipt.get("membership_row_count", Number.class);
            Number objectRows = receipt.get("object_row_count", Number.class);
            if (membershipStart == null || membershipEnd == null
                    || !membershipStart.equals(objectStart) || !membershipEnd.equals(objectEnd)
                    || !membershipStart.isBefore(membershipEnd)
                    || membershipRows == null || objectRows == null
                    || membershipRows.longValue() != objectRows.longValue()) {
                throw incompleteObjects();
            }
            if (previousStart != null && membershipStart.isBefore(previousStart)) {
                throw incompleteObjects();
            }
            previousStart = membershipStart;
            if (!membershipEnd.isAfter(requiredStart)) {
                continue;
            }
            if (latestEnd == null || membershipEnd.isAfter(latestEnd)) {
                latestEnd = membershipEnd;
            }
        }
        /* Feature rows are sparse on market holidays and begin only after the calculator's
           warm-up window.  The manifest/materialization interval is the authoritative requested
           coverage; object receipts describe rows that actually exist and therefore must not be
           forced into a gapless wall-clock interval.  We still require authoritative receipt
           metadata and an output reaching the evaluation boundary. */
        if (latestEnd == null || latestEnd.isBefore(requiredEnd)) {
            throw incompleteObjects();
        }
    }

    private static IllegalStateException incompleteObjects() {
        return new IllegalStateException(
                "Feature output manifest must identify complete versioned " + OUTPUT_SCHEMA + " objects");
    }

    private static String expectedFeedCode(String featureCode, String resolution, String calculatorVersion) {
        return "FEATURE_" + identityPart(featureCode) + "_" + identityPart(resolution) + "_"
                + identityPart(calculatorVersion);
    }

    private static String expectedFeedVersion(String calculatorVersion) {
        return calculatorVersion.replace(':', '-') + "+" + OUTPUT_SCHEMA;
    }

    private static String identityPart(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    static UUID deterministicUuid(Object... values) {
        StringBuilder name = new StringBuilder();
        for (Object value : values) {
            if (!name.isEmpty()) {
                name.append('|');
            }
            name.append(value);
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16)
                    .putLong(UUID_NAMESPACE.getMostSignificantBits())
                    .putLong(UUID_NAMESPACE.getLeastSignificantBits())
                    .array());
            byte[] digest = sha1.digest(name.toString().getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 must be available for UUIDv5", exception);
        }
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
        /* The array must be present, and may be empty. basic-compiled-plan-v2 declares
           minItems 0, and thirteen of the fourteen published Basic elements need no official
           feature at all, so demanding at least one refused release for every strategy built
           without an RSI block. An absent array is still a malformed plan. */
        if (!required.isArray()) {
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
