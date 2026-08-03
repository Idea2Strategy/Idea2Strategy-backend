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
import java.time.LocalDate;
import java.time.ZoneId;
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
 * <p>The contract carries one step sequence and one side for the whole plan, and both consumers
 * implement it that way — D's backtest runtime and C's plan interpreter each apply the single
 * {@code steps} list to every flow. A strategy whose groups disagree on their container or their
 * blocks therefore cannot be expressed, so it is refused here rather than flattened into a plan that
 * would trade the wrong side. Per-flow steps need a contract version agreed with C and D.
 */
public final class StrategyBotCompiledPlanAssembler {

    public static final String CONTRACT_VERSION = "strategy-bot.v1";
    public static final String PLAN_SCHEMA_VERSION = "basic-compiled-plan.v1";

    /**
     * The supported universe is a query over listing and symbol effectivity observed on one market
     * date, not a published artifact, so the date is what identifies the slice a plan was built
     * against. New York is the market whose calendar decides it.
     */
    private static final String INSTRUMENT_CATALOG_PREFIX = "us-supported-universe:";
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private static final String TERMINAL_OPERATION = "EMIT_ORDER_CANDIDATE";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$(?<name>[A-Za-z][A-Za-z0-9_]*)$");
    private static final Pattern SHORTHAND_RESOLUTION =
            Pattern.compile("^(?<amount>[1-9][0-9]*)(?<unit>[smhd])$");
    private static final String CONTAINER_PLACEHOLDER = "container";

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
        Map<String, StrategyFeatureDefinition> features = new HashMap<>();
        catalog.features().forEach(feature -> features.put(feature.featureCode(), feature));

        List<PlanStep> steps = sharedSteps(planRoot, elements);
        List<RequiredFeature> requiredFeatures = requiredFeatures(planRoot, elements, features);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("contractVersion", CONTRACT_VERSION);
        root.put("schemaVersion", PLAN_SCHEMA_VERSION);
        root.put("elementCatalogVersion", catalog.version().catalogVersion());
        root.put("instrumentCatalogVersion", instrumentCatalogVersion(releasedAt));
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
        snapshot.put("initialCashAmount", initialCashAmount.toPlainString());
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
                });

        ArrayNode stepNodes = root.putArray("steps");
        steps.forEach(step -> {
            ObjectNode node = stepNodes.addObject();
            node.put("sequence", step.sequence());
            node.put("operation", step.operation());
            ObjectNode arguments = node.putObject("arguments");
            step.arguments().forEach(arguments::put);
        });

        String checksum = checksum(root, requiredFeatures, steps);
        root.put("planChecksum", checksum);
        return new ContractPlan(CONTRACT_VERSION, PLAN_SCHEMA_VERSION, checksum, canonical(root));
    }

    /**
     * The step sequence every flow shares.
     *
     * <p>Each group is translated independently and the results must agree, because the contract
     * publishes one sequence. Comparing the translated steps rather than the source blocks is what
     * makes the check meaningful: two groups can differ in block ids and still mean the same thing,
     * and two groups can share block ids yet differ in the container their order side comes from.
     */
    private List<PlanStep> sharedSteps(JsonNode planRoot, Map<String, StrategyElementDefinition> elements) {
        JsonNode flows = planRoot.path("flows");
        if (!flows.isArray() || flows.isEmpty()) {
            throw new IllegalStateException("A compiled plan declares no flows");
        }
        List<PlanStep> shared = null;
        for (JsonNode flow : flows) {
            List<PlanStep> steps = steps(flow, elements);
            if (steps.isEmpty()) {
                throw new IllegalStateException("Flow " + flow.path("key").asText() + " declares no steps");
            }
            if (!TERMINAL_OPERATION.equals(steps.getLast().operation())) {
                throw new IllegalStateException(
                        "A compiled plan must end with " + TERMINAL_OPERATION + ", not "
                                + steps.getLast().operation());
            }
            if (shared == null) {
                shared = steps;
            } else if (!shared.equals(steps)) {
                throw new ImmutableStrategyReleaseRejectedException(
                        "This strategy's groups do not compile to one execution sequence, and the "
                                + "published contract carries a single sequence for the whole plan. "
                                + "Release each group as its own strategy until per-group sequences "
                                + "are agreed with the evaluation and backtest runtimes.");
            }
        }
        return shared;
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
        return value.asText();
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
            Map<String, StrategyFeatureDefinition> features) {
        Map<String, Set<String>> instrumentsByFeatureCode = new LinkedHashMap<>();
        for (JsonNode flow : planRoot.path("flows")) {
            Set<String> instruments = new LinkedHashSet<>();
            flow.path("instrumentIds").forEach(node -> instruments.add(node.asText()));
            Set<String> codes = new LinkedHashSet<>();
            flow.path("steps").forEach(step -> {
                StrategyElementDefinition element = elements.get(step.path("elementCode").asText());
                if (element != null) {
                    parse(element.executionContract()).path("backtest").path("features")
                            .forEach(feature -> codes.add(feature.asText()));
                }
            });
            codes.forEach(code -> instrumentsByFeatureCode
                    .computeIfAbsent(code, ignored -> new TreeSet<>())
                    .addAll(instruments));
        }
        if (instrumentsByFeatureCode.isEmpty()) {
            throw new IllegalStateException("A compiled plan requires at least one official feature");
        }

        List<RequiredFeature> requirements = new ArrayList<>();
        new TreeSet<>(instrumentsByFeatureCode.keySet()).forEach(code -> {
            StrategyFeatureDefinition definition = features.get(code);
            if (definition == null) {
                throw new IllegalStateException("Validated feature is missing from its catalog: " + code);
            }
            String resolution = normalizedResolution(definition.resolution());
            requirements.add(new RequiredFeature(
                    requirementId(code, resolution),
                    definition.id().toString(),
                    featureVersion(definition.calculatorVersion()),
                    List.copyOf(instrumentsByFeatureCode.get(code)),
                    resolution,
                    requiredObservations(definition)));
        });
        return List.copyOf(requirements);
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

    private static String instrumentCatalogVersion(Instant releasedAt) {
        return INSTRUMENT_CATALOG_PREFIX + LocalDate.ofInstant(releasedAt, MARKET_ZONE);
    }

    /**
     * The checksum material, which is deliberately built from the assembled values rather than from
     * the serialised document: the consumer recomputes it from the fields it decoded, so anything the
     * two sides disagree about has to be a field, never a formatting choice.
     */
    private String checksum(ObjectNode root, List<RequiredFeature> features, List<PlanStep> steps) {
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
            });
        });
        steps.forEach(step -> {
            material.append('\n').append("step=").append(step.sequence()).append('|').append(step.operation());
            new TreeMap<>(step.arguments()).forEach((name, value) -> material
                    .append('|').append(name).append('=').append(value));
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

    private record RequiredFeature(
            String requirementId,
            String featureId,
            String featureVersion,
            List<String> instruments,
            String resolution,
            int requiredObservations) {}
}
