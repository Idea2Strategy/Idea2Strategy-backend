package com.idea2strategy.backend.messaging.strategybot.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Versioned, deterministic contract fixtures for the B strategy and bot-control boundary.
 *
 * <p>The examples intentionally describe only server-executable Basic plans. They do not expose
 * Pro nodes, user code, external data sources, or a way to submit an order directly.</p>
 */
public final class StrategyBotContractFixtures {

    public static final String CONTRACT_VERSION = "strategy-bot.v1";
    public static final String COMPILED_PLAN_SCHEMA_VERSION = "basic-compiled-plan.v1";
    /**
     * The shape a strategy with more than one trade container is published in.
     *
     * <p>Root #202: version 1 stated one chain and one side for the whole plan, so a buy container
     * and a sell container could not both be described. Version 2 moves them onto the flow.
     */
    public static final String MULTI_CONTAINER_COMPILED_PLAN_SCHEMA_VERSION = "basic-compiled-plan.v2";
    public static final String EXECUTION_SNAPSHOT_SCHEMA_VERSION = "basic-launch-snapshot.v1";

    private static final String BOT_ID = "00000000-0000-4000-8000-000000000201";
    private static final String CORRELATION_ID = "00000000-0000-4000-8000-000000000202";
    private static final String SNAPSHOT_HASH =
            "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    private static final String SEMANTIC_HASH =
            "sha256:2222222222222222222222222222222222222222222222222222222222222222";
    private static final String REQUIRED_FEATURE_SET_HASH =
            "sha256:3333333333333333333333333333333333333333333333333333333333333333";
    private static final String DATASET_MANIFEST_ID = "00000000-0000-4000-8000-000000000203";
    private static final String OCCURRED_AT = "2026-07-31T12:00:00Z";
    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");

    private StrategyBotContractFixtures() {}

    public static FixtureSet standard() {
        var immutableVersion = new ImmutableStrategyVersion(
                EXECUTION_SNAPSHOT_SCHEMA_VERSION,
                SEMANTIC_HASH,
                SNAPSHOT_HASH);
        var executionSnapshot = new ExecutionSnapshot(
                immutableVersion,
                "BASIC",
                "100000.00000000",
                "USD",
                List.of(new Partition(
                        "partition-1",
                        10_000,
                        List.of(new Flow(
                                "flow-1",
                                List.of("00000000-0000-4000-8000-000000000301"))))));
        var draft = new CompiledPlanDraft(
                CONTRACT_VERSION,
                COMPILED_PLAN_SCHEMA_VERSION,
                "basic-elements:2026-07-31",
                "us-supported-universe:2026-07-31",
                "basic-compiler:1.0.0",
                REQUIRED_FEATURE_SET_HASH,
                List.of(new RequiredFeature(
                        "rsi-14-pt1m",
                        "00000000-0000-4000-8000-000000000401",
                        "1.0.0",
                        List.of("00000000-0000-4000-8000-000000000301"),
                        "PT1M",
                        14)),
                executionSnapshot,
                List.of(
                        new PlanStep(1, "LOAD_FEATURE", orderedMap(
                                "feature", "RSI_14",
                                "resolution", "1m")),
                        new PlanStep(2, "COMPARE", orderedMap(
                                "operator", "LT",
                                "threshold", "30")),
                        new PlanStep(3, "EMIT_ORDER_CANDIDATE", orderedMap(
                                "allocation", "EQUAL",
                                "orderType", "MARKET",
                                "side", "BUY"))));
        var compiledPlan = new BasicCompiledPlan(
                draft.contractVersion(),
                draft.schemaVersion(),
                draft.elementCatalogVersion(),
                draft.instrumentCatalogVersion(),
                draft.compilerVersion(),
                draft.requiredFeatureSetHash(),
                draft.requiredFeatures(),
                draft.executionSnapshot(),
                draft.steps(),
                checksum(draft.checksumMaterial()));

        var runCommand = new BotRunCommand(
                metadata(
                        "BOT_RUN_COMMAND",
                        "00000000-0000-4000-8000-000000000211",
                        BOT_ID,
                        SNAPSHOT_HASH,
                        "RUN|2026-08-03T13:30:00Z"),
                BOT_ID,
                SNAPSHOT_HASH,
                "2026-08-03T13:30:00Z",
                null);
        var stopCommand = new BotStopCommand(
                metadata(
                        "BOT_STOP_COMMAND",
                        "00000000-0000-4000-8000-000000000212",
                        BOT_ID,
                        SNAPSHOT_HASH,
                        "STOP|USER_REQUESTED"),
                BOT_ID,
                SNAPSHOT_HASH,
                "USER_REQUESTED");
        var officialBacktestRequest = new OfficialBacktestRequest(
                metadata(
                        "OFFICIAL_BACKTEST_REQUESTED",
                        "00000000-0000-4000-8000-000000000213",
                        BOT_ID,
                        SNAPSHOT_HASH,
                        "OFFICIAL_BACKTEST|" + DATASET_MANIFEST_ID + "|accounting:1.0.0"),
                "00000000-0000-4000-8000-000000000214",
                "BASIC",
                1,
                BOT_ID,
                SNAPSHOT_HASH,
                compiledPlan.planChecksum(),
                DATASET_MANIFEST_ID,
                SNAPSHOT_HASH,
                "2025-01-01",
                "2025-12-31",
                "accounting:1.0.0",
                "backtest-policy:1.0.0",
                "STRATEGY_RELEASE",
                List.of(new PinnedFeatureMaterialization(
                        "00000000-0000-4000-8000-000000000215", "sha256:" + "5".repeat(64))),
                "sha256:4df5ec8056b0857c8c841fc3a9e4f4d75c90196aef1e7ece7716445284523f33");

        return new FixtureSet(compiledPlan, runCommand, stopCommand, officialBacktestRequest);
    }

    /**
     * The run command for a bot a room schedule bounds, which carries both ends of its window.
     *
     * <p>Separate from {@link #standard()} rather than replacing its run command, because a personal
     * bot genuinely has no end to publish: its window closes when its owner stops it. Both shapes are
     * on the wire, so both are pinned.
     */
    public static BotRunCommand roomRunCommand() {
        String eligibleFrom = "2026-08-03T13:30:00Z";
        String eligibleUntil = "2026-08-10T20:00:00Z";
        return new BotRunCommand(
                metadata(
                        "BOT_RUN_COMMAND",
                        "00000000-0000-4000-8000-000000000213",
                        BOT_ID,
                        SNAPSHOT_HASH,
                        "RUN|" + eligibleFrom + ".." + eligibleUntil),
                BOT_ID,
                SNAPSHOT_HASH,
                eligibleFrom,
                eligibleUntil);
    }

    public static String calculatePlanChecksum(BasicCompiledPlan plan) {
        Objects.requireNonNull(plan);
        var draft = new CompiledPlanDraft(
                plan.contractVersion(),
                plan.schemaVersion(),
                plan.elementCatalogVersion(),
                plan.instrumentCatalogVersion(),
                plan.compilerVersion(),
                plan.requiredFeatureSetHash(),
                plan.requiredFeatures(),
                plan.executionSnapshot(),
                plan.steps());
        return checksum(draft.checksumMaterial());
    }

    private static MessageMetadata metadata(
            String messageType,
            String messageId,
            String aggregateId,
            String snapshotHash,
            String operationKey) {
        var idempotencyMaterial = String.join("\n",
                "contractVersion=" + CONTRACT_VERSION,
                "messageType=" + messageType,
                "aggregateId=" + aggregateId,
                "snapshotHash=" + snapshotHash,
                "operationKey=" + operationKey);
        return new MessageMetadata(
                CONTRACT_VERSION,
                messageType,
                messageId,
                OCCURRED_AT,
                CORRELATION_ID,
                checksum(idempotencyMaterial));
    }

    private static Map<String, String> orderedMap(String... entries) {
        var result = new LinkedHashMap<String, String>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return result;
    }

    static String checksum(String material) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public record FixtureSet(
            BasicCompiledPlan compiledPlan,
            BotRunCommand runCommand,
            BotStopCommand stopCommand,
            OfficialBacktestRequest officialBacktestRequest) {
        public FixtureSet {
            Objects.requireNonNull(compiledPlan);
            Objects.requireNonNull(runCommand);
            Objects.requireNonNull(stopCommand);
            Objects.requireNonNull(officialBacktestRequest);
        }
    }

    public record ImmutableStrategyVersion(
            String snapshotSchemaVersion,
            String semanticHash,
            String snapshotHash) {
        public ImmutableStrategyVersion {
            requireText(snapshotSchemaVersion, "snapshotSchemaVersion");
            requireSha256(semanticHash, "semanticHash");
            requireSha256(snapshotHash, "snapshotHash");
        }
    }

    public record ExecutionSnapshot(
            ImmutableStrategyVersion immutableStrategyVersion,
            String mode,
            String initialCashAmount,
            String currency,
            List<Partition> partitions) {
        public ExecutionSnapshot {
            Objects.requireNonNull(immutableStrategyVersion);
            if (!"BASIC".equals(mode)) {
                throw new IllegalArgumentException("Only BASIC execution snapshots belong to this contract");
            }
            requireText(initialCashAmount, "initialCashAmount");
            if (!"USD".equals(currency)) {
                throw new IllegalArgumentException("The first market contract uses USD");
            }
            partitions = List.copyOf(partitions);
            if (partitions.isEmpty()) {
                throw new IllegalArgumentException("At least one partition is required");
            }
        }
    }

    public record Partition(String key, int budgetCapBps, List<Flow> flows) {
        public Partition {
            requireText(key, "partition.key");
            if (budgetCapBps <= 0 || budgetCapBps > 10_000) {
                throw new IllegalArgumentException("budgetCapBps must be between 1 and 10000");
            }
            flows = List.copyOf(flows);
            if (flows.isEmpty()) {
                throw new IllegalArgumentException("At least one flow is required");
            }
        }
    }

    /**
     * One trade container.
     *
     * <p>{@code steps} is this container's own AND chain, present from
     * {@code basic-compiled-plan.v2}. A version 1 plan stated one chain for the whole plan and
     * therefore only one side, which is why a strategy with a buy container and a sell container had
     * no shape to be published in (root #202). It is nullable rather than required so that a plan
     * published before version 2 still binds.
     */
    public record Flow(String key, List<String> officialInstrumentIds, List<PlanStep> steps) {
        public Flow {
            requireText(key, "flow.key");
            officialInstrumentIds = List.copyOf(officialInstrumentIds);
            if (officialInstrumentIds.isEmpty()) {
                throw new IllegalArgumentException("At least one official instrument is required");
            }
            if (steps != null) {
                steps = List.copyOf(steps);
                if (steps.isEmpty()) {
                    throw new IllegalArgumentException(
                            "a container that declares steps must declare at least one");
                }
            }
        }

        /** The version 1 shape: the chain lived on the plan, so a flow carried none. */
        public Flow(String key, List<String> officialInstrumentIds) {
            this(key, officialInstrumentIds, null);
        }
    }

    public record PlanStep(int sequence, String operation, Map<String, String> arguments) {
        public PlanStep {
            if (sequence <= 0) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            requireText(operation, "operation");
            arguments = Map.copyOf(arguments);
        }

        String checksumMaterial() {
            var result = new StringBuilder()
                    .append(sequence)
                    .append('|')
                    .append(operation);
            arguments.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result
                            .append('|')
                            .append(entry.getKey())
                            .append('=')
                            .append(entry.getValue()));
            return result.toString();
        }
    }

    public record RequiredFeature(
            String requirementId,
            String featureId,
            String featureVersion,
            List<String> instruments,
            String resolution,
            int requiredObservations) {
        public RequiredFeature {
            requireText(requirementId, "requiredFeature.requirementId");
            featureId = requireUuid(featureId, "requiredFeature.featureId");
            requireText(featureVersion, "requiredFeature.featureVersion");
            if (!SEMANTIC_VERSION.matcher(featureVersion).matches()) {
                throw new IllegalArgumentException(
                        "requiredFeature.featureVersion must be an exact major.minor.patch version");
            }
            Objects.requireNonNull(instruments, "requiredFeature.instruments");
            var normalizedInstruments = instruments.stream()
                    .map(value -> requireUuid(value, "requiredFeature.instruments"))
                    .sorted()
                    .toList();
            if (normalizedInstruments.isEmpty()) {
                throw new IllegalArgumentException("requiredFeature.instruments must not be empty");
            }
            if (new HashSet<>(normalizedInstruments).size() != normalizedInstruments.size()) {
                throw new IllegalArgumentException("requiredFeature.instruments must not contain duplicates");
            }
            instruments = List.copyOf(normalizedInstruments);
            requireNormalizedResolution(resolution);
            if (requiredObservations <= 0) {
                throw new IllegalArgumentException("requiredFeature.requiredObservations must be positive");
            }
        }

        String checksumMaterial() {
            return new StringBuilder()
                    .append("requirementId=").append(requirementId)
                    .append('|').append("featureId=").append(featureId)
                    .append('|').append("featureVersion=").append(featureVersion)
                    .append('|').append("instruments=").append(String.join(",", instruments))
                    .append('|').append("resolution=").append(resolution)
                    .append('|').append("requiredObservations=").append(requiredObservations)
                    .toString();
        }
    }

    private record CompiledPlanDraft(
            String contractVersion,
            String schemaVersion,
            String elementCatalogVersion,
            String instrumentCatalogVersion,
            String compilerVersion,
            String requiredFeatureSetHash,
            List<RequiredFeature> requiredFeatures,
            ExecutionSnapshot executionSnapshot,
            List<PlanStep> steps) {
        String checksumMaterial() {
            var version = executionSnapshot.immutableStrategyVersion();
            var result = new StringBuilder()
                    .append("contractVersion=").append(contractVersion).append('\n')
                    .append("schemaVersion=").append(schemaVersion).append('\n')
                    .append("snapshotSchemaVersion=").append(version.snapshotSchemaVersion()).append('\n')
                    .append("semanticHash=").append(version.semanticHash()).append('\n')
                    .append("snapshotHash=").append(version.snapshotHash()).append('\n')
                    .append("elementCatalogVersion=").append(elementCatalogVersion).append('\n')
                    .append("instrumentCatalogVersion=").append(instrumentCatalogVersion).append('\n')
                    .append("compilerVersion=").append(compilerVersion).append('\n')
                    .append("requiredFeatureSetHash=").append(requiredFeatureSetHash)
                    .append('\n')
                    .append("mode=").append(executionSnapshot.mode()).append('\n')
                    .append("initialCashAmount=").append(executionSnapshot.initialCashAmount()).append('\n')
                    .append("currency=").append(executionSnapshot.currency());
            requiredFeatures.forEach(feature -> result.append('\n')
                    .append("requiredFeature=").append(feature.checksumMaterial()));
            executionSnapshot.partitions().forEach(partition -> {
                result.append('\n')
                        .append("partition=").append(partition.key())
                        .append('|').append("budgetCapBps=").append(partition.budgetCapBps());
                partition.flows().forEach(flow -> {
                    result.append('\n')
                            .append("flow=").append(flow.key())
                            .append('|').append("officialInstrumentIds=")
                            .append(String.join(",", flow.officialInstrumentIds()));
                    // A container's steps hash immediately after its own flow line, which is where
                    // both consumers append them. Anywhere else and a version 2 plan would fail its
                    // checksum on one side only.
                    if (flow.steps() != null) {
                        flow.steps().forEach(step -> result.append('\n')
                                .append("step=").append(step.checksumMaterial()));
                    }
                });
            });
            // A version 1 plan's single chain, hashed exactly where it always was.
            if (steps != null) {
                steps.forEach(step -> result.append('\n')
                        .append("step=").append(step.checksumMaterial()));
            }
            return result.toString();
        }
    }

    public record BasicCompiledPlan(
            String contractVersion,
            String schemaVersion,
            String elementCatalogVersion,
            String instrumentCatalogVersion,
            String compilerVersion,
            String requiredFeatureSetHash,
            List<RequiredFeature> requiredFeatures,
            ExecutionSnapshot executionSnapshot,
            List<PlanStep> steps,
            String planChecksum) {
        public BasicCompiledPlan {
            ContractVersionGuard.requireSupported(contractVersion);
            requireText(schemaVersion, "schemaVersion");
            requireText(elementCatalogVersion, "elementCatalogVersion");
            requireText(instrumentCatalogVersion, "instrumentCatalogVersion");
            requireText(compilerVersion, "compilerVersion");
            requireSha256(requiredFeatureSetHash, "requiredFeatureSetHash");
            requiredFeatures = List.copyOf(requiredFeatures);
            if (requiredFeatures.isEmpty()) {
                throw new IllegalArgumentException("At least one required feature is required");
            }
            var requirementIds = new HashSet<String>();
            var requirementKeys = new HashSet<String>();
            for (var feature : requiredFeatures) {
                if (!requirementIds.add(feature.requirementId())) {
                    throw new IllegalArgumentException("requiredFeature.requirementId must be unique");
                }
                var key = String.join("|",
                        feature.featureId(),
                        feature.featureVersion(),
                        feature.resolution(),
                        String.join(",", feature.instruments()));
                if (!requirementKeys.add(key)) {
                    throw new IllegalArgumentException("Duplicate required feature is not allowed");
                }
            }
            Objects.requireNonNull(executionSnapshot);
            // A plan states its chain once for the whole plan (version 1) or once per container
            // (version 2), and exactly one of the two. Neither is a plan that would trade on every
            // event; both would leave two disagreeing answers to "what does this container check".
            boolean planWide = steps != null;
            if (planWide) {
                steps = List.copyOf(steps);
                if (steps.isEmpty()) {
                    throw new IllegalArgumentException("At least one compiled step is required");
                }
            }
            boolean perContainer = executionSnapshot.partitions().stream()
                    .flatMap(partition -> partition.flows().stream())
                    .allMatch(flow -> flow.steps() != null);
            if (planWide == perContainer) {
                throw new IllegalArgumentException(
                        "a compiled plan declares its steps for the plan or for every container, "
                                + "never both and never neither");
            }
            requireSha256(planChecksum, "planChecksum");
        }
    }

    public record MessageMetadata(
            String contractVersion,
            String messageType,
            String messageId,
            String occurredAt,
            String correlationId,
            String idempotencyKey) {
        public MessageMetadata {
            ContractVersionGuard.requireSupported(contractVersion);
            requireText(messageType, "messageType");
            requireText(messageId, "messageId");
            requireText(occurredAt, "occurredAt");
            requireText(correlationId, "correlationId");
            requireSha256(idempotencyKey, "idempotencyKey");
        }
    }

    public record BotRunCommand(
            MessageMetadata metadata,
            String botId,
            String expectedSnapshotHash,
            String executionEligibleFrom,
            /**
             * The room schedule's evaluation end, absent for a bot no schedule bounds.
             *
             * <p>C93: the evaluation runtime stops deciding at this boundary rather than when the stop
             * command happens to be delivered, so a room's window is followed rather than hoped for.
             */
            String executionEligibleUntil) {
        public BotRunCommand {
            Objects.requireNonNull(metadata);
            requireText(botId, "botId");
            requireSha256(expectedSnapshotHash, "expectedSnapshotHash");
            requireText(executionEligibleFrom, "executionEligibleFrom");
            if (executionEligibleUntil != null
                    && executionEligibleUntil.compareTo(executionEligibleFrom) <= 0) {
                throw new IllegalArgumentException(
                        "an evaluation window that ends before it opens can never evaluate anything");
            }
        }
    }

    public record BotStopCommand(
            MessageMetadata metadata,
            String botId,
            String expectedSnapshotHash,
            String reasonCode) {
        public BotStopCommand {
            Objects.requireNonNull(metadata);
            requireText(botId, "botId");
            requireSha256(expectedSnapshotHash, "expectedSnapshotHash");
            requireText(reasonCode, "reasonCode");
        }
    }

    public record OfficialBacktestRequest(
            MessageMetadata metadata,
            String runId,
            String lane,
            int aggregateSequence,
            String botId,
            String expectedSnapshotHash,
            String compiledPlanChecksum,
            String datasetManifestId,
            String expectedDatasetHash,
            String periodStart,
            String periodEnd,
            String assumptionsVersion,
            String executionPolicyVersion,
            String requestReason,
            List<PinnedFeatureMaterialization> featureMaterializations,
            String requestHash) {
        public OfficialBacktestRequest {
            Objects.requireNonNull(metadata);
            requireText(runId, "runId");
            if (!"BASIC".equals(lane) || aggregateSequence != 1) {
                throw new IllegalArgumentException("Official release backtests use BASIC lane sequence 1");
            }
            requireText(botId, "botId");
            requireSha256(expectedSnapshotHash, "expectedSnapshotHash");
            requireSha256(compiledPlanChecksum, "compiledPlanChecksum");
            requireText(datasetManifestId, "datasetManifestId");
            requireSha256(expectedDatasetHash, "expectedDatasetHash");
            requireText(periodStart, "periodStart");
            requireText(periodEnd, "periodEnd");
            requireText(assumptionsVersion, "assumptionsVersion");
            requireText(executionPolicyVersion, "executionPolicyVersion");
            if (!"STRATEGY_RELEASE".equals(requestReason)) {
                throw new IllegalArgumentException("Only the official release backtest belongs to this fixture");
            }
            featureMaterializations = List.copyOf(featureMaterializations);
            if (featureMaterializations.isEmpty()) {
                throw new IllegalArgumentException("Official release backtests pin required feature outputs");
            }
            requireSha256(requestHash, "requestHash");
        }
    }

    public record PinnedFeatureMaterialization(String featureMaterializationId, String lockedResultHash) {
        public PinnedFeatureMaterialization {
            requireText(featureMaterializationId, "featureMaterializationId");
            requireSha256(lockedResultHash, "lockedResultHash");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireSha256(String value, String field) {
        requireText(value, field);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must use sha256:<64 lowercase hex>");
        }
    }

    private static String requireUuid(String value, String field) {
        requireText(value, field);
        try {
            var normalized = UUID.fromString(value).toString();
            if (!normalized.equals(value)) {
                throw new IllegalArgumentException(field + " must use canonical lowercase UUID format");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must use canonical lowercase UUID format", exception);
        }
    }

    private static void requireNormalizedResolution(String value) {
        requireText(value, "requiredFeature.resolution");
        try {
            var duration = Duration.parse(value);
            if (duration.isZero() || duration.isNegative() || !duration.toString().equals(value)) {
                throw new IllegalArgumentException(
                        "requiredFeature.resolution must be a positive normalized ISO-8601 duration");
            }
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "requiredFeature.resolution must be a positive normalized ISO-8601 duration", exception);
        }
    }
}
