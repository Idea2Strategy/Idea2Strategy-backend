package com.idea2strategy.backend.persistence.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding;
import java.util.List;

final class StrategyValidationResultJson {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StrategyValidationResultJson() {}

    static String write(List<StrategyValidationFinding> findings) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.set("findings", OBJECT_MAPPER.valueToTree(findings));
        return StrategyDocumentJson.canonicalize(root.toString());
    }

    static List<StrategyValidationFinding> read(String resultDocument) {
        try {
            var root = OBJECT_MAPPER.readTree(resultDocument);
            return List.copyOf(OBJECT_MAPPER.convertValue(
                    root.path("findings"), new TypeReference<List<StrategyValidationFinding>>() {}));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Stored strategy validation result is invalid", exception);
        }
    }
}
