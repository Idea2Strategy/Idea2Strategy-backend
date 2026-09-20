package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.ContractPlan;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.Flow;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only production export boundary for compatibility and deployment verification.
 *
 * <p>It runs semantic documents through the same validator/compiler and final contract assembler as
 * an immutable release, but does not persist a release, plan, or input pin. This keeps cross-runtime
 * verification attached to the producer implementation instead of maintaining a second compiler in
 * integration scripts.
 */
public final class BasicStrategyArtifactExporter {
    private static final String SNAPSHOT_SCHEMA_VERSION = "basic-launch-snapshot.v1";
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final BasicExecutionPlanCompiler compiler = new BasicExecutionPlanCompiler();
    private final StrategyBotCompiledPlanAssembler assembler = new StrategyBotCompiledPlanAssembler();

    public record PartitionSource(UUID partitionId, int budgetCapBps, String semanticDocument) {
        public PartitionSource {
            Objects.requireNonNull(partitionId, "partitionId");
            semanticDocument = StrategyDocumentJson.canonicalize(semanticDocument);
        }
    }

    public ContractPlan export(
            List<PartitionSource> sources,
            BasicStrategyCatalog catalog,
            BigDecimal initialCashAmount,
            Instant exportedAt) {
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("export must contain at least one partition");
        }
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        Objects.requireNonNull(exportedAt, "exportedAt");

        List<StrategyBotCompiledPlanAssembler.PartitionPlan> partitions = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            PartitionSource source = sources.get(index);
            UUID strategyId = derivedId(source.partitionId(), "strategy");
            String presentation = "{}";
            StrategyDocument document = new StrategyDocument(
                    strategyId,
                    source.semanticDocument(),
                    presentation,
                    "basic-semantic/v1",
                    "basic-presentation/v1",
                    StrategyDocumentJson.sha256(source.semanticDocument()),
                    StrategyDocumentJson.sha256(presentation),
                    0,
                    exportedAt,
                    exportedAt);
            UUID planId = derivedId(source.partitionId(), "compiled-plan");
            JsonNode planRoot = parse(compiler.compile(planId, document, catalog, exportedAt).planDocument());
            partitions.add(new StrategyBotCompiledPlanAssembler.PartitionPlan(
                    planRoot,
                    source.partitionId(),
                    source.budgetCapBps(),
                    flows(planRoot, planId, catalog.version().id(), exportedAt, index)));
        }

        String semanticHash = aggregateSemanticHash(sources);
        String snapshotHash = StrategyDocumentJson.sha256(StrategyDocumentJson.canonicalize(
                "{\"semanticHash\":\"" + semanticHash + "\",\"snapshotSchemaVersion\":\""
                        + SNAPSHOT_SCHEMA_VERSION + "\"}"));
        return assembler.assemble(
                partitions,
                catalog,
                initialCashAmount,
                semanticHash,
                snapshotHash,
                SNAPSHOT_SCHEMA_VERSION,
                exportedAt);
    }

    private List<Flow> flows(
            JsonNode planRoot,
            UUID planId,
            UUID catalogId,
            Instant exportedAt,
            int partitionOrder) {
        List<Flow> flows = new ArrayList<>();
        int flowOrder = 0;
        for (JsonNode flowNode : planRoot.path("flows")) {
            String key = flowNode.path("key").asText();
            List<UUID> instruments = new ArrayList<>();
            flowNode.path("instrumentIds").forEach(node -> instruments.add(UUID.fromString(node.asText())));
            String semantic = canonical(flowNode);
            String layout = "{}";
            String configurationHash = StrategyDocumentJson.sha256(
                    "export:" + exportedAt + ":" + partitionOrder);
            flows.add(new Flow(
                    derivedId(planId, "flow:" + key),
                    key,
                    catalogId,
                    planId,
                    semantic,
                    layout,
                    StrategyDocumentJson.sha256(semantic),
                    StrategyDocumentJson.sha256(layout),
                    configurationHash,
                    instruments,
                    List.of(),
                    flowOrder++));
        }
        return List.copyOf(flows);
    }

    private String aggregateSemanticHash(List<PartitionSource> sources) {
        var array = objectMapper.createArrayNode();
        sources.forEach(source -> array.add(parse(source.semanticDocument())));
        return StrategyDocumentJson.sha256(canonical(array));
    }

    private JsonNode parse(String document) {
        try {
            return objectMapper.readTree(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("export document is not valid JSON", exception);
        }
    }

    private String canonical(JsonNode node) {
        try {
            return StrategyDocumentJson.canonicalize(objectMapper.writeValueAsString(node));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("export document cannot be serialized", exception);
        }
    }

    private static UUID derivedId(UUID baseId, String component) {
        return UUID.nameUUIDFromBytes((baseId + ":" + component).getBytes(StandardCharsets.UTF_8));
    }
}
