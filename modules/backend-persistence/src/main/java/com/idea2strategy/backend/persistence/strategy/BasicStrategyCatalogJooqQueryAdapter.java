package com.idea2strategy.backend.persistence.strategy;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryPort;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class BasicStrategyCatalogJooqQueryAdapter implements BasicStrategyCatalogQueryPort {
    private final DSLContext dsl;

    public BasicStrategyCatalogJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ElementCatalogVersion> findLatestPublishedCatalog(Instant at) {
        var catalogs = table(name("strategy", "element_catalog_versions"));
        var id = field(name("id"), UUID.class);
        var language = field(name("language_version"), String.class);
        var schema = field(name("schema_version"), String.class);
        var catalog = field(name("catalog_version"), String.class);
        var dataRequirements = field(name("data_requirement_version"), String.class);
        var definitionHash = field(name("definition_hash"), String.class);
        var publishedAt = field(name("published_at"), OffsetDateTime.class);
        var retiredAt = field(name("retired_at"), OffsetDateTime.class);
        OffsetDateTime observedAt = at.atOffset(ZoneOffset.UTC);

        return dsl.select(id, language, schema, catalog, dataRequirements, definitionHash, publishedAt, retiredAt)
                .from(catalogs)
                .where(publishedAt.le(observedAt)
                        .and(retiredAt.isNull().or(retiredAt.gt(observedAt))))
                .orderBy(publishedAt.desc(), id.desc())
                .limit(1)
                .fetchOptional(record -> new ElementCatalogVersion(
                        record.get(id), record.get(language), record.get(schema), record.get(catalog),
                        record.get(dataRequirements), record.get(definitionHash),
                        record.get(publishedAt).toInstant(),
                        record.get(retiredAt) == null ? null : record.get(retiredAt).toInstant()));
    }

    @Override
    public Optional<ElementCatalogVersion> findPublishedCatalog(UUID catalogId, Instant at) {
        var catalogs = table(name("strategy", "element_catalog_versions"));
        var id = field(name("id"), UUID.class);
        var language = field(name("language_version"), String.class);
        var schema = field(name("schema_version"), String.class);
        var catalog = field(name("catalog_version"), String.class);
        var dataRequirements = field(name("data_requirement_version"), String.class);
        var definitionHash = field(name("definition_hash"), String.class);
        var publishedAt = field(name("published_at"), OffsetDateTime.class);
        var retiredAt = field(name("retired_at"), OffsetDateTime.class);
        OffsetDateTime observedAt = at.atOffset(ZoneOffset.UTC);

        return dsl.select(id, language, schema, catalog, dataRequirements, definitionHash, publishedAt, retiredAt)
                .from(catalogs)
                .where(id.eq(catalogId)
                        .and(publishedAt.le(observedAt))
                        .and(retiredAt.isNull().or(retiredAt.gt(observedAt))))
                .fetchOptional(record -> new ElementCatalogVersion(
                        record.get(id),
                        record.get(language),
                        record.get(schema),
                        record.get(catalog),
                        record.get(dataRequirements),
                        record.get(definitionHash),
                        record.get(publishedAt).toInstant(),
                        record.get(retiredAt) == null ? null : record.get(retiredAt).toInstant()));
    }

    @Override
    public Optional<ElementCatalogVersion> findPublishedCatalog(
            String languageVersion, String schemaVersion, String catalogVersion, Instant at) {
        var catalogs = table(name("strategy", "element_catalog_versions"));
        var id = field(name("id"), UUID.class);
        var language = field(name("language_version"), String.class);
        var schema = field(name("schema_version"), String.class);
        var catalog = field(name("catalog_version"), String.class);
        var dataRequirements = field(name("data_requirement_version"), String.class);
        var definitionHash = field(name("definition_hash"), String.class);
        var publishedAt = field(name("published_at"), OffsetDateTime.class);
        var retiredAt = field(name("retired_at"), OffsetDateTime.class);
        OffsetDateTime observedAt = at.atOffset(ZoneOffset.UTC);

        return dsl.select(id, language, schema, catalog, dataRequirements, definitionHash, publishedAt, retiredAt)
                .from(catalogs)
                .where(language.eq(languageVersion)
                        .and(schema.eq(schemaVersion))
                        .and(catalog.eq(catalogVersion))
                        .and(publishedAt.le(observedAt))
                        .and(retiredAt.isNull().or(retiredAt.gt(observedAt))))
                .fetchOptional(record -> new ElementCatalogVersion(
                        record.get(id),
                        record.get(language),
                        record.get(schema),
                        record.get(catalog),
                        record.get(dataRequirements),
                        record.get(definitionHash),
                        record.get(publishedAt).toInstant(),
                        record.get(retiredAt) == null ? null : record.get(retiredAt).toInstant()));
    }

    @Override
    public List<StrategyElementDefinition> findElements(UUID catalogId) {
        return elementQuery(catalogId, null);
    }

    @Override
    public Optional<StrategyElementDefinition> findPublishedElement(
            UUID catalogId, String elementCode, Instant at) {
        var elements = table(name("strategy", "element_definitions")).as("element");
        var catalogs = table(name("strategy", "element_catalog_versions")).as("catalog");
        var id = field(name("element", "id"), UUID.class);
        var elementCatalogId = field(name("element", "element_catalog_version_id"), UUID.class);
        var catalogIdField = field(name("catalog", "id"), UUID.class);
        var code = field(name("element", "element_code"), String.class);
        var kind = field(name("element", "element_kind"), String.class);
        var parameters = field(name("element", "parameter_schema"), JSONB.class);
        var inputs = field(name("element", "input_port_schema"), JSONB.class);
        var outputs = field(name("element", "output_port_schema"), JSONB.class);
        var contract = field(name("element", "execution_contract"), JSONB.class);
        var definitionHash = field(name("element", "definition_hash"), String.class);
        var publishedAt = field(name("catalog", "published_at"), OffsetDateTime.class);
        var retiredAt = field(name("catalog", "retired_at"), OffsetDateTime.class);
        OffsetDateTime observedAt = at.atOffset(ZoneOffset.UTC);

        return dsl.select(id, elementCatalogId, code, kind, parameters, inputs, outputs, contract, definitionHash)
                .from(elements)
                .join(catalogs)
                .on(catalogIdField.eq(elementCatalogId))
                .where(elementCatalogId
                        .eq(catalogId)
                        .and(code.eq(elementCode))
                        .and(publishedAt.le(observedAt))
                        .and(retiredAt.isNull().or(retiredAt.gt(observedAt))))
                .fetchOptional(record -> new StrategyElementDefinition(
                        record.get(id),
                        record.get(elementCatalogId),
                        record.get(code),
                        record.get(kind),
                        record.get(parameters).data(),
                        record.get(inputs).data(),
                        record.get(outputs).data(),
                        record.get(contract).data(),
                        record.get(definitionHash)));
    }

    private List<StrategyElementDefinition> elementQuery(UUID catalogId, String requestedCode) {
        var elements = table(name("strategy", "element_definitions"));
        var id = field(name("id"), UUID.class);
        var catalog = field(name("element_catalog_version_id"), UUID.class);
        var code = field(name("element_code"), String.class);
        var kind = field(name("element_kind"), String.class);
        var parameters = field(name("parameter_schema"), JSONB.class);
        var inputs = field(name("input_port_schema"), JSONB.class);
        var outputs = field(name("output_port_schema"), JSONB.class);
        var contract = field(name("execution_contract"), JSONB.class);
        var definitionHash = field(name("definition_hash"), String.class);
        var condition = catalog.eq(catalogId);
        if (requestedCode != null) {
            condition = condition.and(code.eq(requestedCode));
        }

        return dsl.select(id, catalog, code, kind, parameters, inputs, outputs, contract, definitionHash)
                .from(elements)
                .where(condition)
                .orderBy(code)
                .fetch(record -> new StrategyElementDefinition(
                        record.get(id),
                        record.get(catalog),
                        record.get(code),
                        record.get(kind),
                        record.get(parameters).data(),
                        record.get(inputs).data(),
                        record.get(outputs).data(),
                        record.get(contract).data(),
                        record.get(definitionHash)));
    }

    @Override
    public List<StrategyFeatureDefinition> findFeatures(UUID catalogId) {
        var features = table(name("market_data", "feature_definitions"));
        var id = field(name("id"), UUID.class);
        var catalog = field(name("element_catalog_version_id"), UUID.class);
        var code = field(name("feature_code"), String.class);
        var calculatorVersion = field(name("calculator_version"), String.class);
        var resolution = field(name("resolution"), String.class);
        var parameters = field(name("normalized_parameters"), JSONB.class);
        var outputType = field(name("output_value_type"), String.class);
        var historyPoints = field(name("required_history_points"), Integer.class);
        var definitionHash = field(name("definition_hash"), String.class);

        return dsl.select(
                        id, catalog, code, calculatorVersion, resolution, parameters, outputType, historyPoints, definitionHash)
                .from(features)
                .where(catalog.eq(catalogId))
                .orderBy(code, calculatorVersion, resolution)
                .fetch(record -> new StrategyFeatureDefinition(
                        record.get(id),
                        record.get(catalog),
                        record.get(code),
                        record.get(calculatorVersion),
                        record.get(resolution),
                        record.get(parameters).data(),
                        record.get(outputType),
                        record.get(historyPoints),
                        record.get(definitionHash)));
    }

    @Override
    public List<SupportedInstrument> findSupportedInstruments(Instant at, LocalDate marketDate) {
        var instruments = table(name("market_data", "instruments")).as("instrument");
        var symbols = table(name("market_data", "instrument_symbols")).as("symbol");
        var instrumentId = field(name("instrument", "id"), UUID.class);
        var symbolInstrumentId = field(name("symbol", "instrument_id"), UUID.class);
        var assetType = field(name("instrument", "asset_type"), String.class).cast(String.class);
        var exchange = field(name("instrument", "primary_exchange_mic"), String.class);
        var currency = field(name("instrument", "currency_code"), String.class);
        var listedAt = field(name("instrument", "listed_at"), LocalDate.class);
        var symbol = field(name("symbol", "symbol"), String.class);
        var effectiveFrom = field(name("symbol", "effective_from"), OffsetDateTime.class);
        OffsetDateTime observedAt = at.atOffset(ZoneOffset.UTC);

        return dsl.selectDistinct(instrumentId, assetType, exchange, currency, symbol)
                .from(instruments)
                .join(symbols)
                .on(symbolInstrumentId.eq(instrumentId))
                .where(assetType.in("STOCK", "ETF")
                        .and(currency.eq("USD"))
                        .and(listedAt.isNull().or(listedAt.le(marketDate)))
                        .and(effectiveFrom.le(observedAt)))
                .orderBy(symbol, exchange)
                .fetch(record -> new SupportedInstrument(
                        record.get(instrumentId),
                        record.get(assetType),
                        record.get(exchange).trim(),
                        record.get(currency).trim(),
                        record.get(symbol)));
    }
}
