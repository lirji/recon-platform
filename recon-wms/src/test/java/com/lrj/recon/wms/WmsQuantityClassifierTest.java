package com.lrj.recon.wms;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WmsQuantityClassifierTest {
    @Test
    void incompleteWatermarkIsNotQuantityMismatch() {
        WmsQuantityFact left = fact("F1", "EA", "4", true, null);
        WmsQuantityFact right = fact("F1", "EA", "4", false, null);
        List<WmsQuantityClassifier.Finding> findings = new WmsQuantityClassifier()
                .classify(List.of(left), List.of(right));
        assertEquals(1, findings.size());
        assertEquals(WmsQuantityCodes.SOURCE_INCOMPLETE, findings.getFirst().code());
    }

    @Test
    void quantityAndUnitDifferencesDoNotUseMoney() {
        String jsonl = "{\"factId\":\"B1/C1\",\"enterpriseId\":\"ENT-1\",\"warehouseId\":\"WH-A\","
                + "\"ownerId\":\"OWNER-1\",\"skuId\":\"SKU-Q\",\"businessLotKey\":\"NO_LOT\","
                + "\"serialId\":null,\"unit\":\"EA\",\"quantity\":\"4\",\"factKind\":\"POSTED\"}";
        List<WmsQuantityFact> left = WmsSnapshotParser.parseJsonl(jsonl, true);
        assertEquals(0, left.getFirst().quantity().compareTo(new BigDecimal("4")));
        WmsQuantityFact unitRight = fact("B1/C1", "KG", "4", true, null);
        assertTrue(new WmsQuantityClassifier().classify(left, List.of(unitRight)).stream()
                .anyMatch(finding -> WmsQuantityCodes.UNIT_MISMATCH.equals(finding.code())));
        WmsQuantityFact qtyRight = fact("B1/C1", "EA", "5", true, null);
        assertTrue(new WmsQuantityClassifier().classify(left, List.of(qtyRight)).stream()
                .anyMatch(finding -> WmsQuantityCodes.QTY_MISMATCH.equals(finding.code())));
        assertThrows(IllegalArgumentException.class,
                () -> WmsSnapshotParser.parseJsonl(jsonl.replace("\"unit\":\"EA\"", "\"currency\":\"USD\""), true));
    }

    @Test
    void serialConflictAndMissingSides() {
        WmsQuantityFact left = fact("SN-1", "EA", "1", true, "SN-1");
        WmsQuantityFact right = fact("SN-1", "EA", "1", true, "SN-2");
        assertTrue(new WmsQuantityClassifier().classify(List.of(left), List.of(right)).stream()
                .anyMatch(finding -> WmsQuantityCodes.SERIAL_CONFLICT.equals(finding.code())));
        assertTrue(new WmsQuantityClassifier().classify(List.of(left), List.of()).stream()
                .anyMatch(finding -> WmsQuantityCodes.MISSING_RIGHT.equals(finding.code())));
    }

    private static WmsQuantityFact fact(String factId, String unit, String qty, boolean complete, String serial) {
        return new WmsQuantityFact(factId, "ENT-1", "WH-A", "OWNER-1", "SKU-Q", "NO_LOT", serial, unit,
                new BigDecimal(qty), "POSTED", complete);
    }
}
