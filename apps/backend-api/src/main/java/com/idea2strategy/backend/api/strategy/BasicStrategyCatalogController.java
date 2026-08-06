package com.idea2strategy.backend.api.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalog;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategy-catalogs/basic")
@ConditionalOnBean(BasicStrategyCatalogQueryService.class)
public class BasicStrategyCatalogController {
    private final BasicStrategyCatalogQueryService queryService;
    private final ObjectMapper objectMapper;

    public BasicStrategyCatalogController(BasicStrategyCatalogQueryService queryService) {
        this.queryService = queryService;
        this.objectMapper = new ObjectMapper();
    }

    @GetMapping
    public BasicStrategyCatalogResponse getPublished(
            @RequestParam(required = false) String languageVersion,
            @RequestParam(required = false) String schemaVersion,
            @RequestParam(required = false) String catalogVersion) {
        if (languageVersion == null && schemaVersion == null && catalogVersion == null) {
            return response(queryService.getLatestPublished());
        }
        requireText(languageVersion, "languageVersion");
        requireText(schemaVersion, "schemaVersion");
        requireText(catalogVersion, "catalogVersion");
        return response(queryService.getPublished(languageVersion, schemaVersion, catalogVersion));
    }

    @GetMapping("/instruments")
    public SupportedInstrumentsResponse getSupportedInstruments() {
        return new SupportedInstrumentsResponse(queryService.getSupportedInstruments().stream()
                .map(instrument -> new InstrumentResponse(
                        instrument.id(),
                        instrument.assetType(),
                        instrument.primaryExchangeMic(),
                        instrument.currencyCode(),
                        instrument.symbol()))
                .toList());
    }

    private BasicStrategyCatalogResponse response(BasicStrategyCatalog catalog) {
        var version = catalog.version();
        return new BasicStrategyCatalogResponse(
                new CatalogVersionResponse(
                        version.id(),
                        version.languageVersion(),
                        version.schemaVersion(),
                        version.catalogVersion(),
                        version.dataRequirementVersion(),
                        version.definitionHash(),
                        version.publishedAt(),
                        version.retiredAt()),
                catalog.elements().stream()
                        .map(element -> new ElementResponse(
                                element.id(),
                                element.catalogId(),
                                element.elementCode(),
                                element.elementKind(),
                                readJson(element.parameterSchema()),
                                readJson(element.inputPortSchema()),
                                readJson(element.outputPortSchema()),
                                readJson(element.executionContract()),
                                element.definitionHash()))
                        .toList(),
                catalog.features().stream()
                        .map(feature -> new FeatureResponse(
                                feature.id(),
                                feature.catalogId(),
                                feature.featureCode(),
                                feature.calculatorVersion(),
                                feature.resolution(),
                                readJson(feature.normalizedParameters()),
                                feature.outputValueType(),
                                feature.requiredHistoryPoints(),
                                feature.definitionHash()))
                        .toList(),
                catalog.instruments().stream()
                        .map(instrument -> new InstrumentResponse(
                                instrument.id(),
                                instrument.assetType(),
                                instrument.primaryExchangeMic(),
                                instrument.currencyCode(),
                                instrument.symbol()))
                        .toList());
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Published strategy catalog contains invalid JSON", exception);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public record BasicStrategyCatalogResponse(
            CatalogVersionResponse version,
            List<ElementResponse> elements,
            List<FeatureResponse> features,
            List<InstrumentResponse> instruments) {}

    public record SupportedInstrumentsResponse(List<InstrumentResponse> instruments) {}

    public record CatalogVersionResponse(
            UUID id,
            String languageVersion,
            String schemaVersion,
            String catalogVersion,
            String dataRequirementVersion,
            String definitionHash,
            Instant publishedAt,
            Instant retiredAt) {}

    public record ElementResponse(
            UUID id,
            UUID catalogId,
            String elementCode,
            String elementKind,
            Map<String, Object> parameterSchema,
            Map<String, Object> inputPortSchema,
            Map<String, Object> outputPortSchema,
            Map<String, Object> executionContract,
            String definitionHash) {}

    public record FeatureResponse(
            UUID id,
            UUID catalogId,
            String featureCode,
            String calculatorVersion,
            String resolution,
            Map<String, Object> normalizedParameters,
            String outputValueType,
            int requiredHistoryPoints,
            String definitionHash) {}

    public record InstrumentResponse(
            UUID id,
            String assetType,
            String primaryExchangeMic,
            String currencyCode,
            String symbol) {}
}
