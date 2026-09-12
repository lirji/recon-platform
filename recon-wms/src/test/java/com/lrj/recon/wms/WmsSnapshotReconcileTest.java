package com.lrj.recon.wms;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WmsSnapshotReconcileTest {
    @Test
    void consumeApproveAndRecheckClosesRepairedCase() {
        WmsSnapshotReconcile recon = new WmsSnapshotReconcile();
        String left = fact("4");
        String rightMismatch = fact("5");
        List<WmsSnapshotReconcile.Case> opened = recon.consume(left, true, rightMismatch, true);
        assertEquals(1, opened.size());
        assertEquals(WmsQuantityCodes.QTY_MISMATCH, opened.getFirst().code());
        assertEquals(1, opened.getFirst().wmsSchema());
        assertEquals(1, opened.getFirst().reconSchema());
        WmsSnapshotReconcile.Case approved = recon.approve(opened.getFirst().id());
        assertEquals("REMEDIATING", approved.state());
        List<WmsSnapshotReconcile.Case> closed = recon.consume(left, true, fact("4"), true);
        assertEquals("CLOSED", closed.getFirst().state());
        assertEquals(1, recon.contract().get("wmsSchemaVersion"));
    }

    private static String fact(String qty) {
        return "{\"factId\":\"B1/C1\",\"enterpriseId\":\"ENT-1\",\"warehouseId\":\"WH-A\","
                + "\"ownerId\":\"OWNER-1\",\"skuId\":\"SKU-Q\",\"businessLotKey\":\"NO_LOT\","
                + "\"serialId\":null,\"unit\":\"EA\",\"quantity\":\"" + qty + "\",\"factKind\":\"POSTED\"}";
    }
}
