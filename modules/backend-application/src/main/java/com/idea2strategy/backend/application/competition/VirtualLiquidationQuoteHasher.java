package com.idea2strategy.backend.application.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VirtualLiquidationQuoteHasher {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private VirtualLiquidationQuoteHasher() {}

    public static String hash(VirtualLiquidationQuote quote) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("quoteContractVersion", quote.quoteContractVersion());
        payload.put("quoteRulesVersion", quote.quoteRulesVersion());
        payload.put("roomId", quote.roomId().toString());
        payload.put("participationId", quote.participationId().toString());
        payload.put("botId", quote.botId().toString());
        payload.put("evaluationSegmentId", quote.evaluationSegmentId().toString());
        payload.put("cutoffAt", quote.cutoffAt().toString());
        payload.put("sourceEventSequence", quote.sourceEventSequence());
        payload.put("currentCashAmount", amount(quote.currentCashAmount()));
        payload.put("netLiquidationCashDelta", amount(quote.netLiquidationCashDelta()));
        payload.put("equityHistory", quote.equityHistory().stream().map(observation -> Map.of(
                "sourceEventSequence", observation.sourceEventSequence(),
                "equityAmount", amount(observation.equityAmount()))).toList());
        payload.put("producerCalculatedSharpeRatio", quote.producerCalculatedSharpeRatio() == null
                ? null : amount(quote.producerCalculatedSharpeRatio()));
        payload.put("liquidatedPositionCount", quote.liquidatedPositionCount());
        payload.put("grossProceedsAmount", amount(quote.grossProceedsAmount()));
        payload.put("grossCostAmount", amount(quote.grossCostAmount()));
        payload.put("feeAmount", amount(quote.feeAmount()));
        payload.put("feePolicyId", quote.feePolicyId().toString());
        payload.put("feeRulesHash", quote.feeRulesHash());
        payload.put("slippageRateBps", quote.slippageRateBps());
        payload.put("ledgerStateHash", quote.ledgerStateHash());
        payload.put("positionStateHash", quote.positionStateHash());
        payload.put("sourceSetHash", quote.sourceSetHash());
        try {
            byte[] canonical = OBJECT_MAPPER.writeValueAsBytes(payload);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("virtual liquidation quote is not JSON serializable", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static boolean matches(VirtualLiquidationQuote quote) {
        byte[] expected = HexFormat.of().parseHex(hash(quote).substring("sha256:".length()));
        byte[] supplied = HexFormat.of().parseHex(quote.quoteHash().substring("sha256:".length()));
        return MessageDigest.isEqual(expected, supplied);
    }

    private static String amount(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_EVEN).toPlainString();
    }
}
