package com.lrj.recon.wms;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 解析 S8-01 JSONL。禁止读出 currency/amountMinor。 */
public final class WmsSnapshotParser {
    private WmsSnapshotParser() {
    }

    public static List<WmsQuantityFact> parseJsonl(String payload, boolean watermarksComplete) {
        List<WmsQuantityFact> facts = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return facts;
        }
        for (String line : payload.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.contains("\"currency\"") || line.contains("\"amountMinor\"")) {
                throw new IllegalArgumentException("WMS quantity snapshot must not carry money fields");
            }
            facts.add(new WmsQuantityFact(
                    text(line, "factId"),
                    text(line, "enterpriseId"),
                    text(line, "warehouseId"),
                    text(line, "ownerId"),
                    text(line, "skuId"),
                    text(line, "businessLotKey"),
                    emptyToNull(text(line, "serialId")),
                    text(line, "unit"),
                    new BigDecimal(text(line, "quantity")),
                    text(line, "factKind"),
                    watermarksComplete));
        }
        return facts;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() || "null".equals(value) ? null : value;
    }

    private static String text(String json, String name) {
        String key = "\"" + name + "\":";
        int start = json.indexOf(key);
        if (start < 0) {
            return "";
        }
        start += key.length();
        if (start < json.length() && json.charAt(start) == '"') {
            start++;
            int end = json.indexOf('"', start);
            return end < 0 ? "" : json.substring(start, end);
        }
        int end = start;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        String raw = json.substring(start, end).trim();
        return "null".equals(raw) ? "" : raw;
    }
}
