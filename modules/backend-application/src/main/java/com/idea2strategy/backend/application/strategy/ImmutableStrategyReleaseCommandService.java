package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.FeatureRequirement;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.Flow;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.LaunchConfiguration;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.Partition;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ImmutableStrategyReleaseCommandService {
    private static final String SNAPSHOT_SCHEMA_VERSION = "basic-launch-snapshot.v1";
    private final ImmutableStrategyReleaseCommandPort releasePort;
    private final BasicExecutionPlanCommandService planService;
    private final StrategyValidationRunQueryPort validationPort;
    private final StrategyQueryPort strategyPort;
    private final StrategyDocumentQueryPort documentPort;
    private final CurrentPrincipal principal;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public ImmutableStrategyReleaseCommandService(
            ImmutableStrategyReleaseCommandPort releasePort,
            BasicExecutionPlanCommandService planService,
            StrategyValidationRunQueryPort validationPort,
            StrategyQueryPort strategyPort,
            StrategyDocumentQueryPort documentPort,
            CurrentPrincipal principal,
            Clock clock) {
        this.releasePort = Objects.requireNonNull(releasePort, "releasePort");
        this.planService = Objects.requireNonNull(planService, "planService");
        this.validationPort = Objects.requireNonNull(validationPort, "validationPort");
        this.strategyPort = Objects.requireNonNull(strategyPort, "strategyPort");
        this.documentPort = Objects.requireNonNull(documentPort, "documentPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ImmutableStrategyRelease release(
            UUID validationRunId,
            BasicStrategyCatalog catalog,
            ImmutableStrategyReleaseCommand command) {
        Objects.requireNonNull(validationRunId, "validationRunId");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(command, "command");

        var plan = planService.compile(validationRunId, catalog);
        UUID ownerId = principal.accountId();
        var validation = validationPort.findOwnedById(validationRunId, ownerId)
                .orElseThrow(() -> new NoSuchElementException("Strategy validation not found"));
        if (validation.status() != StrategyValidationStatus.VALID) {
            throw new IllegalStateException("Strategy validation must be VALID");
        }
        var strategy = strategyPort.findOwnedById(validation.strategyId(), ownerId)
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));
        if (strategy.mode() != StrategyMode.BASIC) {
            throw new IllegalArgumentException("Strategy must use BASIC mode");
        }
        var document = documentPort.findOwnedByStrategyId(strategy.id(), ownerId)
                .orElseThrow(() -> new NoSuchElementException("Strategy document not found"));
        if (!validation.semanticHash().equals(document.semanticHash())
                || validation.requestedEditSequence() != document.editSequence()) {
            throw new IllegalStateException("Strategy validation is stale");
        }

        String configurationDocument = configurationDocument(command);
        String configurationHash = StrategyDocumentJson.sha256(configurationDocument);
        var configuration = new LaunchConfiguration(
                command.initialCashAmount(), command.brokerRulesVersion(), command.accountingRulesVersion(),
                command.precisionRulesVersion(), command.feePolicyId(), command.buyingPowerBufferPolicyId(),
                command.candidateConflictPolicy(), configurationHash);

        JsonNode planRoot = parse(plan.planDocument());
        JsonNode presentationRoot = parse(document.presentationDocument());
        List<Flow> flows = flows(
                command.releaseId(), planRoot, presentationRoot, plan.id(), catalog, configurationHash);
        var partition = new Partition(
                derivedId(command.releaseId(), "partition"), strategy.name(), strategy.description(),
                command.budgetCapBps(),
                configurationHash, flows);

        String semanticSnapshot = semanticSnapshot(planRoot, command.budgetCapBps(), configurationHash);
        String presentationSnapshot = presentationSnapshot(strategy.name(), strategy.description(), presentationRoot);
        String semanticHash = StrategyDocumentJson.sha256(semanticSnapshot);
        String presentationHash = StrategyDocumentJson.sha256(presentationSnapshot);
        String snapshotHash = snapshotHash(semanticHash, presentationHash, configurationHash);
        var release = new ImmutableStrategyRelease(
                command.releaseId(), ownerId, strategy.name(), strategy.description(), semanticSnapshot,
                presentationSnapshot, semanticHash, presentationHash, snapshotHash, configuration, partition,
                clock.instant());

        return releasePort.saveOnce(
                release, validationRunId, validation.requestedEditSequence(), validation.semanticHash());
    }

    private List<Flow> flows(
            UUID releaseId,
            JsonNode planRoot,
            JsonNode presentationRoot,
            UUID planId,
            BasicStrategyCatalog catalog,
            String configurationHash) {
        Map<String, UUID> featureIds = new HashMap<>();
        catalog.features().forEach(feature -> featureIds.put(feature.featureCode(), feature.id()));
        Map<String, Set<String>> elementFeatures = new HashMap<>();
        catalog.elements().forEach(element -> elementFeatures.put(
                element.elementCode(), featureCodes(parse(element.executionContract()))));

        var result = new ArrayList<Flow>();
        int order = 0;
        for (JsonNode flowNode : planRoot.path("flows")) {
            String key = flowNode.path("key").asText();
            List<UUID> instruments = new ArrayList<>();
            flowNode.path("instrumentIds").forEach(node -> instruments.add(UUID.fromString(node.asText())));
            Set<UUID> requiredFeatures = new LinkedHashSet<>();
            flowNode.path("steps").forEach(step -> elementFeatures
                    .getOrDefault(step.path("elementCode").asText(), Set.of())
                    .forEach(code -> requiredFeatures.add(requireFeatureId(featureIds, code))));
            List<FeatureRequirement> requirements = new ArrayList<>();
            for (UUID instrumentId : instruments) {
                requiredFeatures.forEach(featureId -> requirements.add(
                        new FeatureRequirement(instrumentId, featureId)));
            }
            String semantic = canonical(flowNode);
            JsonNode position = presentationRoot.path("positions").path(key);
            String layout = canonical(position.isMissingNode() ? objectMapper.createObjectNode() : position);
            result.add(new Flow(
                    derivedId(releaseId, "flow:" + key), key, catalog.version().id(), planId, semantic, layout,
                    StrategyDocumentJson.sha256(semantic), StrategyDocumentJson.sha256(layout), configurationHash,
                    instruments, requirements, order++));
        }
        return List.copyOf(result);
    }

    private String configurationDocument(ImmutableStrategyReleaseCommand command) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("accountingRulesVersion", command.accountingRulesVersion());
        root.put("brokerRulesVersion", command.brokerRulesVersion());
        root.set("candidateConflictPolicy", parse(command.candidateConflictPolicy()));
        root.put("currencyCode", "USD");
        root.put("feePolicyId", command.feePolicyId().toString());
        root.put("initialCashAmount", command.initialCashAmount());
        root.put("precisionRulesVersion", command.precisionRulesVersion());
        root.put("buyingPowerBufferPolicyId", command.buyingPowerBufferPolicyId().toString());
        root.put("slippageRateBps", 5);
        return canonical(root);
    }

    private String semanticSnapshot(JsonNode planRoot, int budgetCapBps, String configurationHash) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
        root.put("mode", "BASIC");
        root.put("partitionBudgetCapBps", budgetCapBps);
        root.put("launchConfigurationHash", configurationHash);
        root.set("compiledPlan", planRoot);
        return canonical(root);
    }

    private String presentationSnapshot(String name, String description, JsonNode presentationRoot) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
        root.put("name", name);
        if (description != null) {
            root.put("description", description);
        }
        root.set("strategyPresentation", presentationRoot);
        return canonical(root);
    }

    private String snapshotHash(String semanticHash, String presentationHash, String configurationHash) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("configurationHash", configurationHash);
        root.put("presentationHash", presentationHash);
        root.put("semanticHash", semanticHash);
        return StrategyDocumentJson.sha256(canonical(root));
    }

    private Set<String> featureCodes(JsonNode executionContract) {
        Set<String> result = new LinkedHashSet<>();
        executionContract.path("backtest").path("features").forEach(node -> result.add(node.asText()));
        return result;
    }

    private UUID requireFeatureId(Map<String, UUID> featureIds, String code) {
        UUID id = featureIds.get(code);
        if (id == null) {
            throw new IllegalStateException("Validated feature is missing from its catalog: " + code);
        }
        return id;
    }

    private UUID derivedId(UUID releaseId, String component) {
        return UUID.nameUUIDFromBytes((releaseId + ":" + component).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode parse(String document) {
        try {
            return objectMapper.readTree(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Release document is not valid JSON", exception);
        }
    }

    private String canonical(JsonNode node) {
        try {
            return StrategyDocumentJson.canonicalize(objectMapper.writeValueAsString(node));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Release document could not be serialized", exception);
        }
    }
}
