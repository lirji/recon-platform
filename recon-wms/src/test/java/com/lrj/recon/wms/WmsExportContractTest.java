package com.lrj.recon.wms;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 消费 WMS WarehouseQuantityFact v1 JSONL。字段来自 wms-inventory SnapshotExportService.toJson，
 * 不是 Money，也不编造单位/效期默认。
 */
class WmsExportContractTest {
    private static final String WMS_LINE = "{\"factId\":\"BAL-1/C-SNAP\",\"factVersion\":1,\"schemaVersion\":1,"
            + "\"enterpriseId\":\"ENT-1\",\"sourceSystem\":\"wms-inventory\",\"scenarioCode\":\"WMS_ONHAND_QTY\","
            + "\"side\":\"WMS\",\"warehouseId\":\"WH-A\",\"ownerId\":\"OWNER-1\",\"skuId\":\"SKU-Q\","
            + "\"businessLotKey\":\"NO_LOT\",\"serialId\":null,\"factKind\":\"POSTED\",\"physicalStatus\":\"ON_HAND\","
            + "\"stockSyncStatus\":\"POSTED\",\"quantity\":\"4\",\"unit\":\"EA\",\"cutoffId\":\"C-SNAP\","
            + "\"sourceWatermark\":\"POST-1\"}";

    @Test
    void consumeWmsExportShapeAndRejectMoneyFields() {
        WmsSnapshotReconcile recon = new WmsSnapshotReconcile();
        String otherQty = WMS_LINE.replace("\"quantity\":\"4\"", "\"quantity\":\"5\"");
        List<WmsSnapshotReconcile.Case> opened = recon.consume(WMS_LINE, true, otherQty, true);
        assertEquals(1, opened.size());
        assertEquals(WmsQuantityCodes.QTY_MISMATCH, opened.getFirst().code());
        assertEquals(1, opened.getFirst().wmsSchema());
        IllegalArgumentException money = assertThrows(IllegalArgumentException.class,
                () -> WmsSnapshotParser.parseJsonl(WMS_LINE.replace("\"unit\":\"EA\"",
                        "\"unit\":\"EA\",\"currency\":\"CNY\",\"amountMinor\":400"), true));
        assertTrue(money.getMessage().contains("money"));
        List<WmsQuantityFact> facts = WmsSnapshotParser.parseJsonl(WMS_LINE, true);
        assertEquals(1, facts.size());
        assertEquals("4", facts.getFirst().quantity().stripTrailingZeros().toPlainString());
        assertEquals("EA", facts.getFirst().unit());
    }
}
