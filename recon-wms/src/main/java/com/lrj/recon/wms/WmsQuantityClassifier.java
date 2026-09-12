package com.lrj.recon.wms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 两侧数量事实判差。不构造 Money，不改金额分类器。
 */
public final class WmsQuantityClassifier {
    public record Finding(String matchKey, String code, String detail) {
    }

    public List<Finding> classify(List<WmsQuantityFact> left, List<WmsQuantityFact> right) {
        Map<String, WmsQuantityFact> lefts = index(left);
        Map<String, WmsQuantityFact> rights = index(right);
        List<Finding> findings = new ArrayList<>();
        if (left.stream().anyMatch(fact -> !fact.watermarksComplete())
                || right.stream().anyMatch(fact -> !fact.watermarksComplete())) {
            findings.add(new Finding("*", WmsQuantityCodes.SOURCE_INCOMPLETE, "watermark incomplete"));
            return findings;
        }
        for (String key : lefts.keySet()) {
            WmsQuantityFact l = lefts.get(key);
            WmsQuantityFact r = rights.get(key);
            if (r == null) {
                findings.add(new Finding(key, WmsQuantityCodes.MISSING_RIGHT, "right missing"));
                continue;
            }
            if (!Objects.equals(l.unit(), r.unit())) {
                findings.add(new Finding(key, WmsQuantityCodes.UNIT_MISMATCH, l.unit() + "!=" + r.unit()));
            } else if (l.quantity().compareTo(r.quantity()) != 0) {
                findings.add(new Finding(key, WmsQuantityCodes.QTY_MISMATCH, l.quantity() + "!=" + r.quantity()));
            }
            if (!Objects.equals(nullToEmpty(l.businessLotKey()), nullToEmpty(r.businessLotKey()))) {
                findings.add(new Finding(key, WmsQuantityCodes.LOT_MISMATCH, "lot differs"));
            }
            if (!Objects.equals(nullToEmpty(l.serialId()), nullToEmpty(r.serialId()))
                    && (l.serialId() != null || r.serialId() != null)) {
                findings.add(new Finding(key, WmsQuantityCodes.SERIAL_CONFLICT, "serial differs"));
            }
        }
        for (String key : rights.keySet()) {
            if (!lefts.containsKey(key)) {
                findings.add(new Finding(key, WmsQuantityCodes.MISSING_LEFT, "left missing"));
            }
        }
        return findings;
    }

    private static Map<String, WmsQuantityFact> index(List<WmsQuantityFact> facts) {
        Map<String, WmsQuantityFact> index = new LinkedHashMap<>();
        for (WmsQuantityFact fact : facts) {
            index.put(fact.matchKey(), fact);
        }
        return index;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
