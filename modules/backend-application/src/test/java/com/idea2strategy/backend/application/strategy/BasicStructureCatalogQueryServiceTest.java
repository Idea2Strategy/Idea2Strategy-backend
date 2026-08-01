package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicStructureCatalogQueryServiceTest {
    private static final UUID CATALOG_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T03:00:00Z");

    @Test
    void returnsVersionFixedBuySellTemplatesAndPackagesWithAllMaterialValuesUnset() {
        var service = service(List.of(
                candidate("SELL_DIRECTION", BasicStructureKind.SELL_TEMPLATE, "SELL"),
                candidate("RSI_STRUCTURE", BasicStructureKind.PACKAGE, "BUY"),
                candidate("BUY_DIRECTION", BasicStructureKind.BUY_TEMPLATE, "BUY")));

        var structures = service.getPublished(catalog());

        assertThat(structures)
                .extracting(BasicStructureVersion::kind, BasicStructureVersion::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BasicStructureKind.BUY_TEMPLATE, "BUY_DIRECTION"),
                        org.assertj.core.groups.Tuple.tuple(BasicStructureKind.SELL_TEMPLATE, "SELL_DIRECTION"),
                        org.assertj.core.groups.Tuple.tuple(BasicStructureKind.PACKAGE, "RSI_STRUCTURE"));
        assertThat(structures).allSatisfy(structure -> {
            assertThat(structure.elementCatalogVersionId()).isEqualTo(CATALOG_ID);
            assertThat(structure.version()).isEqualTo("1.0.0");
            assertThat(structure.nameI18n()).containsEntry("ko", structure.code());
            assertThat(structure.flowDocument()).contains("\"period\":null");
        });
    }

    @Test
    void rejectsAHiddenDefaultOrAnUnofficialElementInsteadOfReturningIt() {
        String valueFilled = document(BasicStructureKind.BUY_TEMPLATE, "BUY")
                .replace("\"period\":null", "\"period\":14");
        assertThatThrownBy(() -> service(List.of(candidate("BUY_DIRECTION", valueFilled)))
                        .getPublished(catalog()))
                .isInstanceOf(InvalidBasicStructureDefinitionException.class)
                .hasMessageContaining("material values must be unset");

        String unofficial = document(BasicStructureKind.BUY_TEMPLATE, "BUY")
                .replace("\"elementCode\":\"RSI\"", "\"elementCode\":\"CUSTOM_CODE\"");
        assertThatThrownBy(() -> service(List.of(candidate("BUY_DIRECTION", unofficial)))
                        .getPublished(catalog()))
                .isInstanceOf(InvalidBasicStructureDefinitionException.class)
                .hasMessageContaining("unofficial element");
    }

    @Test
    void rejectsProCatalogMismatchOrTamperedContentAndRequiresBothBasicTemplateSides() {
        String pro = document(BasicStructureKind.BUY_TEMPLATE, "BUY")
                .replace("\"mode\":\"BASIC\"", "\"mode\":\"PRO\"");
        assertThatThrownBy(() -> service(List.of(candidate("BUY_DIRECTION", pro)))
                        .getPublished(catalog()))
                .isInstanceOf(InvalidBasicStructureDefinitionException.class)
                .hasMessageContaining("BASIC mode");

        BasicStructureCandidate tampered = candidate("BUY_DIRECTION", BasicStructureKind.BUY_TEMPLATE, "BUY");
        tampered = new BasicStructureCandidate(
                tampered.id(), tampered.packageId(), tampered.code(), tampered.version(),
                tampered.elementCatalogVersionId(), tampered.nameDocument(), tampered.descriptionDocument(),
                tampered.flowDocument(),
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                tampered.publishedAt());
        BasicStructureCandidate finalTampered = tampered;
        assertThatThrownBy(() -> service(List.of(finalTampered)).getPublished(catalog()))
                .isInstanceOf(InvalidBasicStructureDefinitionException.class)
                .hasMessageContaining("content hash");

        assertThatThrownBy(() -> service(List.of(candidate(
                                "BUY_DIRECTION", BasicStructureKind.BUY_TEMPLATE, "BUY")))
                        .getPublished(catalog()))
                .isInstanceOf(InvalidBasicStructureDefinitionException.class)
                .hasMessageContaining("BUY and SELL templates");
    }

    private static BasicStructureCatalogQueryService service(List<BasicStructureCandidate> candidates) {
        BasicStructureCatalogQueryPort port = (catalogId, publishedAt) -> candidates;
        return new BasicStructureCatalogQueryService(port, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BasicStructureCandidate candidate(
            String code, BasicStructureKind kind, String container) {
        return candidate(code, document(kind, container));
    }

    private static BasicStructureCandidate candidate(String code, String document) {
        String canonical = StrategyDocumentJson.canonicalize(document);
        return new BasicStructureCandidate(
                UUID.nameUUIDFromBytes((code + ":version").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                code,
                "1.0.0",
                CATALOG_ID,
                "{\"ko\":\"" + code + "\",\"en\":\"" + code + "\"}",
                "{\"ko\":\"구조 설명\",\"en\":\"Structure description\"}",
                canonical,
                StrategyDocumentJson.sha256(canonical),
                NOW.minusSeconds(60));
    }

    private static String document(BasicStructureKind kind, String container) {
        return "{\"mode\":\"BASIC\",\"kind\":\"" + kind + "\",\"container\":\"" + container + "\","
                + "\"instrumentIds\":[],\"blocks\":[{\"id\":\"indicator\",\"elementCode\":\"RSI\","
                + "\"parameters\":{\"period\":null}}],\"connections\":[]}";
    }

    private static BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID,
                        "basic/v1",
                        "schema/v1",
                        "catalog/v1",
                        "data/v1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        NOW.minusSeconds(3600),
                        null),
                List.of(new StrategyElementDefinition(
                        UUID.randomUUID(),
                        CATALOG_ID,
                        "RSI",
                        "BLOCK",
                        "{\"type\":\"object\",\"properties\":{\"period\":{\"type\":\"integer\"}},"
                                + "\"required\":[\"period\"]}",
                        "{}",
                        "{}",
                        "{\"containers\":[\"BUY\",\"SELL\"]}",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")),
                List.of(),
                List.of());
    }
}
