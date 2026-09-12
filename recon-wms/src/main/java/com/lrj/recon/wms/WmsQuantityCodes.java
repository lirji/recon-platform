package com.lrj.recon.wms;

/** WMS 数量场景稳定编码。不进入 Money 币种。 */
public final class WmsQuantityCodes {
    public static final String SCENARIO_ONHAND = "WMS_ONHAND_QTY";
    public static final String CLEAN = "CLEAN";
    public static final String SOURCE_INCOMPLETE = "SOURCE_INCOMPLETE";
    public static final String UNIT_MISMATCH = "UNIT_MISMATCH";
    public static final String QTY_MISMATCH = "QTY_MISMATCH";
    public static final String LOT_MISMATCH = "LOT_MISMATCH";
    public static final String SERIAL_CONFLICT = "SERIAL_CONFLICT";
    public static final String MISSING_LEFT = "MISSING_LEFT";
    public static final String MISSING_RIGHT = "MISSING_RIGHT";

    private WmsQuantityCodes() {
    }
}
