package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.ContractPlan;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.Flow;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles the {@code strategy-bot.v1} compiled-plan document C's runtime loads a bot from.
 *
 * <p>Every input already exists at release time — the compiled plan, the element catalog, the feature
 * catalog, the launch configuration and the snapshot hashes — but nothing assembled them into the
 * published contract, so no real bot could start (root #190). This is that assembly, run where the
 * launch snapshot is already being written so the document and the hash it pins are produced in one
 * transaction and can never disagree.
 *
 * <p>Two translations carry product meaning rather than plumbing, and both are the element catalog's
 * to state:
 *
 * <ul>
 *   <li><strong>Element code to runtime step.</strong> Each element's
 *       {@code executionContract.runtime} declares the {@code operation} and its {@code arguments},
 *       where a {@code $name} value is a placeholder filled from the block's parameters and
 *       {@code $container} from the group's trade container. The mapping therefore lives in the
 *       catalog both runtimes already read, not in code either runtime would have to duplicate.
 *   <li><strong>Feature catalog to warm-up requirement.</strong> {@code calculator_version}
 *       {@code rsi:1.0.0} becomes the contract's exact {@code 1.0.0}, the catalog's {@code 1m}
 *       resolution becomes the normalised {@code PT1M} the consumer validates, and
 *       {@code required_history_points} becomes {@code requiredObservations} one lower: the window
 *       counts every point it needs including the live bar that triggers the evaluation, so warm-up
 *       has to supply all but that one. Requiring the full count instead would be safe but would
 *       delay every bot's first decision by a bar and would disagree with the pinned contract sample.
 * </ul>
 *
 * <p><strong>One container per trade side.</strong> A Basic strategy is a buy container and a sell
 * container, and the blocks inside a container are an AND chain, so each flow carries its own
 * {@code side}, {@code allocation} and {@code steps}. Version 1 of the contract put them on the plan
 * and could therefore describe only one container, which is why that ordinary strategy used to be
 * refused at release (root #202).
 */
public final class StrategyBotCompiledPlanAssembler {

    public static final String CONTRACT_VERSION = "strategy-bot.v1";
    /**
     * The shape this assembler publishes.
     *
     * <p>Version 2, always. A Basic strategy is one container per trade side and each container is
     * its own AND chain of blocks, so {@code side}, {@code allocation} and {@code steps} belong to
     * the flow. Version 1 put them on the plan and could therefore describe only one container,
     * which is why a strategy with a buy rule and a sell rule was refused at release (root #202).
     *
     * <p>A single-container strategy is published as version 2 too. One live shape for everything
     * newly released is worth more than saving a few bytes: version 1 survives only because plans
     * already published exist in it, and both consumers still read it.
     */
    public static final String PLAN_SCHEMA_VERSION = "basic-compiled-plan.v2";

    private static final String INSTRUMENT_CATALOG_PREFIX = "us-supported-universe:";

    /**
     * The supported-universe version the consumer actually implements.
     *
     * <p>This used to be the release date in the New York market zone, on the reasoning that the
     * universe is a query over listing effectivity rather than a published artifact. That produced a
     * new contract version every market day, and the Backtest runtime whitelists only the versions it
     * implements — deliberately, because accepting an arbitrary {@code us-supported-universe:*} would
     * let official instrument ids be silently re-pointed. So a release on 2026-08-09 published
     * {@code us-supported-universe:2026-08-09}, the consumer implemented
     * {@code us-supported-universe:2026-07-31}, and the run failed
     * {@code INSTRUMENT_CATALOG_VERSION_UNSUPPORTED} before simulation (backend #257, INT03 run
     * 9d4a31d5).
     *
     * <p>It is a published contract value on both sides, so it is pinned rather than derived: the
     * consumer's list lives in {@code backtest_engine/basic_runtime.py} as
     * {@code INSTRUMENT_CATALOG_VERSIONS}, and the two must name the same version. The value moves
     * only when a new universe is actually published, in the same change that adds it to the consumer.
     */
    public static final String PUBLISHED_INSTRUMENT_CATALOG_VERSION =
            INSTRUMENT_CATALOG_PREFIX + "2026-07-31";

    private static final String TERMINAL_OPERATION = "EMIT_ORDER_CANDIDATE";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$(?<name>[A-Za-z][A-Za-z0-9_]*)$");
    private static final Pattern SHORTHAND_RESOLUTION =
            Pattern.compile("^(?<amount>[1-9][0-9]*)(?<unit>[smhd])$");
    private static final String CONTAINER_PLACEHOLDER = "container";
    private static final Set<String> LIVE_RESOLUTIONS = Set.of("30m", "1h", "4h", "1d");

    /**
     * The compiled-plan contract spells money as a fixed 8-decimal string
     * ({@code ^-?[0-9]{1,16}\.[0-9]{8}$}), and the public release API accepts any scale a caller
     * writes. A release requesting {@code 100000} therefore produced a plan the Backtest runtime
     * rejected before simulation, and the run failed with a contract violation rather than a result
     * (backend #255, INT03 run 66956d2d). Normalizing at this producer boundary keeps one amount to
     * one spelling for every caller — API, CLI or UI — instead of loosening the consumer's contract.
     */
    private static final int MONEY_SCALE = 8;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /**
     * Assembles the document for one release.
     *
     * @param planRoot the compiled plan document {@link BasicExecutionPlanCompiler} produced
     * @param catalog the catalog version the strategy was validated against
     * @param partitionId the canonical partition the flows belong to, which becomes the contract's
     *     partition key: the key only has to identify the partition within the plan, and reusing the
     *     canonical id keeps the published document traceable to the row it describes
     */
    public ContractPlan assemble(
            JsonNode planRoot,
            BasicStrategyCatalog catalog,
            UUID partitionId,
            int budgetCapBps,
            BigDecimal initialCashAmount,
            List<Flow> flows,
            String semanticHash,
            String snapshotHash,
            String snapshotSchemaVersion,
            Instant releasedAt) {
        Objects.requireNonNull(planRoot, "planRoot");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(partitionId, "partitionId");
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        Objects.requireNonNull(flows, "flows");
        Objects.requireNonNull(releasedAt, "releasedAt");

        Map<String, StrategyElementDefinition> elements = new HashMap<>();
        catalog.elements().forEach(element -> elements.put(element.elementCode(), element));
        Map<FeatureRequirementKey, StrategyFeatureDefinition> features = new HashMap<>();
        catalog.features().forEach(feature -> features.put(
                new FeatureRequirementKey(
                        feature.featureCode(), normalizedResolution(feature.resolution())),
                feature));

        Map<String, List<PlanStep>> containers = containersByKey(planRoot, elements);
        List<RequiredFeature> requiredFeatures = requiredFeatures(planRoot, elements, features);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("contractVersion", CONTRACT_VERSION);
        root.put("schemaVersion", PLAN_SCHEMA_VERSION);
        root.put("elementCatalogVersion", catalog.version().catalogVersion());
        root.put("instrumentCatalogVersion", PUBLISHED_INSTRUMENT_CATALOG_VERSION);
        root.put("compilerVersion", requiredText(planRoot, "compilerVersion"));
        root.put("requiredFeatureSetHash", prefixed(requiredText(planRoot, "requiredFeatureSetHash")));

        ArrayNode featureNodes = root.putArray("requiredFeatures");
        requiredFeatures.forEach(feature -> {
            ObjectNode node = featureNodes.addObject();
            node.put("requirementId", feature.requirementId());
            node.put("featureId", feature.featureId());
            node.put("featureVersion", feature.featureVersion());
            ArrayNode instruments = node.putArray("instruments");
            feature.instruments().forEach(instruments::add);
            node.put("resolution", feature.resolution());
            node.put("requiredObservations", feature.requiredObservations());
        });

        ObjectNode snapshot = root.putObject("executionSnapshot");
        ObjectNode version = snapshot.putObject("immutableStrategyVersion");
        version.put("snapshotSchemaVersion", snapshotSchemaVersion);
        version.put("semanticHash", prefixed(semanticHash));
        version.put("snapshotHash", prefixed(snapshotHash));
        snapshot.put("mode", "BASIC");
        snapshot.put("initialCashAmount", moneyAmount(initialCashAmount));
        snapshot.put("currency", "USD");
        ArrayNode partitions = snapshot.putArray("partitions");
        ObjectNode partition = partitions.addObject();
        partition.put("key", partitionId.toString());
        partition.put("budgetCapBps", budgetCapBps);
        ArrayNode flowNodes = partition.putArray("flows");
        flows.stream()
                .sorted(java.util.Comparator.comparingInt(Flow::positionOrder))
                .forEach(flow -> {
                    ObjectNode node = flowNodes.addObject();
                    node.put("key", flow.name());
                    ArrayNode instruments = node.putArray("officialInstrumentIds");
                    flow.instrumentIds().stream().map(UUID::toString).sorted().forEach(instruments::add);
                    List<PlanStep> container = containers.get(flow.name());
                    if (container == null) {
                        throw new IllegalStateException(
                                "released flow " + flow.name() + " has no compiled container: the "
                                        + "release and the compiled plan disagree about which flows exist");
                    }
                    appendSteps(node.putArray("steps"), container);
                });

        String checksum = checksum(root, requiredFeatures);
        root.put("planChecksum", checksum);
        return new ContractPlan(CONTRACT_VERSION, PLAN_SCHEMA_VERSION, checksum, canonical(root));
    }

    /**
     * Each container's own step sequence, keyed by the flow key it belongs to.
     *
     * <p>One entry per group, because a group <em>is</em> a trade container: a strategy with a buy
     * rule and a sell rule is two groups whose sequences differ and whose sides differ. Version 1 of
     * the contract carried one sequence for the whole plan, so that ordinary strategy could not be
     * published at all and this method used to refuse it (root #202). Nothing is compared across
     * containers now — a difference between them is the point, not a conflict.
     */
    private Map<String, List<PlanStep>> containersByKey(
            JsonNode planRoot, Map<String, StrategyElementDefinition> elements) {
        JsonNode flows = planRoot.path("flows");
        if (!flows.isArray() || flows.isEmpty()) {
            throw new IllegalStateException("A compiled plan declares no flows");
        }
        Map<String, List<PlanStep>> containers = new LinkedHashMap<>();
        for (JsonNode flow : flows) {
            String key = requiredText(flow, "key");
            List<PlanStep> steps = steps(flow, elements);
            if (steps.isEmpty()) {
                throw new IllegalStateException("Flow " + key + " declares no steps");
            }
            if (!TERMINAL_OPERATION.equals(steps.getLast().operation())) {
                throw new IllegalStateException(
                        "Container " + key + " must end with " + TERMINAL_OPERATION + ", not "
                                + steps.getLast().operation());
            }
            if (steps.size() < 2) {
                throw new IllegalStateException(
                        "Container " + key + " states no condition before " + TERMINAL_OPERATION
                                + ": an unconditional container would trade on every event");
            }
            if (containers.put(key, steps) != null) {
                throw new IllegalStateException("Flow key " + key + " is declared more than once");
            }
        }
        return containers;
    }

    /** One container's steps, written as the contract carries them. */
    private static void appendSteps(ArrayNode target, List<PlanStep> steps) {
        steps.forEach(step -> {
            ObjectNode node = target.addObject();
            node.put("sequence", step.sequence());
            node.put("operation", step.operation());
            ObjectNode arguments = node.putObject("arguments");
            step.arguments().forEach(arguments::put);
        });
    }

    private List<PlanStep> steps(JsonNode flow, Map<String, StrategyElementDefinition> elements) {
        String container = flow.path("container").asText();
        List<PlanStep> steps = new ArrayList<>();
        for (JsonNode step : flow.path("steps")) {
            String elementCode = requiredText(step, "elementCode");
            StrategyElementDefinition element = elements.get(elementCode);
            if (element == null) {
                throw new IllegalStateException("Validated element is missing from its catalog: " + elementCode);
            }
            JsonNode runtime = parse(element.executionContract()).path("runtime");
            String operation = requiredText(runtime, "operation");
            JsonNode template = runtime.path("arguments");
            if (!template.isObject()) {
                throw new IllegalStateException(
                        "Element " + elementCode + " declares no runtime arguments");
            }
            Map<String, String> arguments = new TreeMap<>();
            template.properties().forEach(argument -> arguments.put(
                    argument.getKey(),
                    resolve(elementCode, argument.getKey(), argument.getValue(), step.path("parameters"), container)));
            steps.add(new PlanStep(step.path("sequence").asInt(), operation, Map.copyOf(arguments)));
        }
        return List.copyOf(steps);
    }

    /**
     * One runtime argument, with {@code $name} placeholders filled in.
     *
     * <p>Every value the contract carries is text, so a numeric parameter is published exactly as the
     * validated document spelled it. Rendering it through the JSON node rather than reformatting the
     * number keeps the checksum a function of what the user actually saved.
     */
    private String resolve(
            String elementCode, String argument, JsonNode template, JsonNode parameters, String container) {
        if (!template.isTextual()) {
            throw new IllegalStateException(
                    "Element " + elementCode + " argument " + argument + " must be declared as text");
        }
        Matcher placeholder = PLACEHOLDER.matcher(template.textValue());
        if (!placeholder.matches()) {
            return template.textValue();
        }
        String name = placeholder.group("name");
        if (CONTAINER_PLACEHOLDER.equals(name)) {
            if (container.isBlank()) {
                throw new IllegalStateException("Flow declares no trade container");
            }
            return container;
        }
        JsonNode value = parameters.path(name);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalStateException(
                    "Element " + elementCode + " needs parameter " + name + ", which the validated block omits");
        }
        String resolved = value.asText();
        return "resolution".equals(argument) ? liveResolution(resolved) : resolved;
    }

    /**
     * The warm-up requirements, one per feature and resolution, over every instrument that needs it.
     *
     * <p>Grouped rather than emitted per flow because the contract forbids a duplicate
     * {@code featureId|featureVersion|resolution|instruments} key, and two flows over the same
     * instruments requiring the same feature would produce exactly that.
     */
    private List<RequiredFeature> requiredFeatures(
            JsonNode planRoot,
            Map<String, StrategyElementDefinition> elements,
            Map<FeatureRequirementKey, StrategyFeatureDefinition> features) {
        Map<FeatureRequirementKey, Set<String>> instrumentsByFeature = new LinkedHashMap<>();
        for (JsonNode flow : planRoot.path("flows")) {
            Set<String> instruments = new LinkedHashSet<>();
            flow.path("instrumentIds").forEach(node -> instruments.add(node.asText()));
            Set<FeatureRequirementKey> requirements = new LinkedHashSet<>();
            flow.path("steps").forEach(step -> {
                StrategyElementDefinition element = elements.get(step.path("elementCode").asText());
                if (element != null) {
                    parse(element.executionContract()).path("backtest").path("features")
                            .forEach(feature -> {
                                String requested = step.path("parameters").path("resolution").asText();
                                FeatureRequirementKey key = resolveFeatureRequirement(
                                        features, feature.asText(), requested);
                                StrategyFeatureDefinition definition = features.get(key);
                                if (definition == null) {
                                    throw new IllegalStateException(
                                            "Validated feature is missing from its catalog: "
                                                    + feature.asText() + "@" + requested);
                                }
                                requirements.add(key);
                            });
                }
            });
            requirements.forEach(requirement -> instrumentsByFeature
                    .computeIfAbsent(requirement, ignored -> new TreeSet<>())
                    .addAll(instruments));
        }
        List<RequiredFeature> requirements = new ArrayList<>();
        instrumentsByFeature.keySet().stream()
                .sorted(java.util.Comparator.comparing(FeatureRequirementKey::featureCode)
                        .thenComparing(FeatureRequirementKey::resolution))
                .forEach(requirement -> {
            StrategyFeatureDefinition definition = features.get(requirement);
            if (definition == null) {
                throw new IllegalStateException(
                        "Validated feature is missing from its catalog: " + requirement.featureCode());
            }
            requirements.add(new RequiredFeature(
                    requirementId(requirement.featureCode(), requirement.resolution()),
                    definition.id().toString(),
                    featureVersion(definition.calculatorVersion()),
                    List.copyOf(instrumentsByFeature.get(requirement)),
                    requirement.resolution(),
                    requiredObservations(definition)));
        });
        return List.copyOf(requirements);
    }

    private FeatureRequirementKey resolveFeatureRequirement(
            Map<FeatureRequirementKey, StrategyFeatureDefinition> features,
            String featureCode,
            String requested) {
        if (!requested.isBlank()) {
            return new FeatureRequirementKey(featureCode, normalizedResolution(liveResolution(requested)));
        }
        List<FeatureRequirementKey> matches = features.keySet().stream()
                .filter(key -> key.featureCode().equals(featureCode))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        throw new IllegalStateException(
                "Feature " + featureCode + " must select exactly one live resolution");
    }

    /**
     * The warm-up count for one feature.
     *
     * <p>{@code required_history_points} counts every point the calculator's window holds, and the
     * live bar that triggers an evaluation is the last of them, so warm-up supplies one fewer. A
     * feature needing a single point needs no history at all, which the contract cannot express, so it
     * is refused rather than published as a warm-up of zero.
     */
    private int requiredObservations(StrategyFeatureDefinition definition) {
        int observations = definition.requiredHistoryPoints() - 1;
        if (observations <= 0) {
            throw new IllegalStateException(
                    "Feature " + definition.featureCode() + " declares no warm-up history, which the "
                            + "contract cannot publish");
        }
        return observations;
    }

    private static String requirementId(String featureCode, String resolution) {
        return (featureCode + "-" + resolution).toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** {@code rsi:1.0.0} names the calculator; the contract carries only its exact version. */
    private static String featureVersion(String calculatorVersion) {
        int separator = calculatorVersion.lastIndexOf(':');
        return separator < 0 ? calculatorVersion : calculatorVersion.substring(separator + 1);
    }

    /**
     * The catalog's {@code 1m} shorthand as the normalised ISO-8601 duration the consumer validates.
     *
     * <p>An already normalised value passes through, so a catalog that starts publishing ISO-8601
     * needs no change here.
     */
    private static String normalizedResolution(String resolution) {
        Matcher shorthand = SHORTHAND_RESOLUTION.matcher(resolution.trim().toLowerCase(Locale.ROOT));
        Duration duration;
        if (shorthand.matches()) {
            long amount = Long.parseLong(shorthand.group("amount"));
            duration = switch (shorthand.group("unit")) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        } else {
            try {
                duration = Duration.parse(resolution);
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "Feature resolution " + resolution + " is not a duration the contract can carry",
                        exception);
            }
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException("Feature resolution must be positive, got " + resolution);
        }
        return duration.toString();
    }

    private static String liveResolution(String resolution) {
        String normalized = resolution.trim().toLowerCase(Locale.ROOT);
        if (!LIVE_RESOLUTIONS.contains(normalized)) {
            throw new IllegalStateException(
                    "Live strategy resolution must be one of 30m, 1h, 4h, 1d, got " + resolution);
        }
        return normalized;
    }

    /**
     * The checksum material, which is deliberately built from the assembled values rather than from
     * the serialised document: the consumer recomputes it from the fields it decoded, so anything the
     * two sides disagree about has to be a field, never a formatting choice.
     */
    private String checksum(ObjectNode root, List<RequiredFeature> features) {
        JsonNode snapshot = root.path("executionSnapshot");
        JsonNode version = snapshot.path("immutableStrategyVersion");
        StringBuilder material = new StringBuilder()
                .append("contractVersion=").append(root.path("contractVersion").asText()).append('\n')
                .append("schemaVersion=").append(root.path("schemaVersion").asText()).append('\n')
                .append("snapshotSchemaVersion=").append(version.path("snapshotSchemaVersion").asText())
                .append('\n')
                .append("semanticHash=").append(version.path("semanticHash").asText()).append('\n')
                .append("snapshotHash=").append(version.path("snapshotHash").asText()).append('\n')
                .append("elementCatalogVersion=").append(root.path("elementCatalogVersion").asText())
                .append('\n')
                .append("instrumentCatalogVersion=").append(root.path("instrumentCatalogVersion").asText())
                .append('\n')
                .append("compilerVersion=").append(root.path("compilerVersion").asText()).append('\n')
                .append("requiredFeatureSetHash=").append(root.path("requiredFeatureSetHash").asText())
                .append('\n')
                .append("mode=").append(snapshot.path("mode").asText()).append('\n')
                .append("initialCashAmount=").append(snapshot.path("initialCashAmount").asText()).append('\n')
                .append("currency=").append(snapshot.path("currency").asText());
        features.forEach(feature -> material.append('\n')
                .append("requiredFeature=")
                .append("requirementId=").append(feature.requirementId())
                .append('|').append("featureId=").append(feature.featureId())
                .append('|').append("featureVersion=").append(feature.featureVersion())
                .append('|').append("instruments=").append(String.join(",", feature.instruments()))
                .append('|').append("resolution=").append(feature.resolution())
                .append('|').append("requiredObservations=").append(feature.requiredObservations()));
        snapshot.path("partitions").forEach(partition -> {
            material.append('\n')
                    .append("partition=").append(partition.path("key").asText())
                    .append('|').append("budgetCapBps=").append(partition.path("budgetCapBps").asInt());
            partition.path("flows").forEach(flow -> {
                material.append('\n').append("flow=").append(flow.path("key").asText())
                        .append('|').append("officialInstrumentIds=");
                List<String> instruments = new ArrayList<>();
                flow.path("officialInstrumentIds").forEach(node -> instruments.add(node.asText()));
                material.append(String.join(",", instruments));
                // The container's steps, hashed immediately after its own flow line. Both consumers
                // append them in this position; anywhere else and every plan would fail its checksum
                // on one side only.
                flow.path("steps").forEach(step -> {
                    material.append('\n').append("step=").append(step.path("sequence").asInt())
                            .append('|').append(step.path("operation").asText());
                    JsonNode arguments = step.path("arguments");
                    new TreeSet<>(arguments.propertyStream().map(Map.Entry::getKey).toList())
                            .forEach(name -> material
                                    .append('|').append(name).append('=')
                                    .append(arguments.path(name).asText()));
                });
            });
        });
        return digest(material.toString());
    }

    private static String digest(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String prefixed(String hash) {
        return hash.startsWith("sha256:") ? hash : "sha256:" + hash;
    }

    /**
     * The contract's fixed 8-decimal spelling of an amount. See {@link #MONEY_SCALE}.
     *
     * <p>{@link java.math.RoundingMode#UNNECESSARY} rather than a rounding mode: an amount carrying
     * more than eight decimals is a caller error, and silently discarding the remainder would make
     * the plan disagree with the release that asked for it. The checksum reads this field back out of
     * the document, so normalizing here is also what keeps the checksum a function of one spelling.
     */
    private static String moneyAmount(BigDecimal amount) {
        try {
            return amount.setScale(MONEY_SCALE, java.math.RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "initialCashAmount " + amount.toPlainString() + " carries more precision than the "
                            + "compiled-plan contract's " + MONEY_SCALE + " decimal places",
                    exception);
        }
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("A compiled plan must carry " + field);
        }
        return value.textValue();
    }

    private JsonNode parse(String document) {
        try {
            return objectMapper.readTree(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Pinned catalog document is not valid JSON", exception);
        }
    }

    private String canonical(JsonNode node) {
        try {
            return StrategyDocumentJson.canonicalize(objectMapper.writeValueAsString(node));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Compiled plan contract could not be serialized", exception);
        }
    }

    private record PlanStep(int sequence, String operation, Map<String, String> arguments) {}

    private record FeatureRequirementKey(String featureCode, String resolution) {}

    private record RequiredFeature(
            String requirementId,
            String featureId,
            String featureVersion,
            List<String> instruments,
            String resolution,
            int requiredObservations) {}
}
