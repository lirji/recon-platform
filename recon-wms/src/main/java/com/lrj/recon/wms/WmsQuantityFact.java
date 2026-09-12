package com.lrj.recon.wms;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * WarehouseQuantityFact v1。数量是十进制，单位是独立字段。
 */
public record WmsQuantityFact(
        String factId,
        String enterpriseId,
        String warehouseId,
        String ownerId,
        String skuId,
        String businessLotKey,
        String serialId,
        String unit,
        BigDecimal quantity,
        String factKind,
        boolean watermarksComplete) {

    public WmsQuantityFact {
        Objects.requireNonNull(factId, "factId");
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(quantity, "quantity");
        if ("USD".equals(unit) || "CNY".equals(unit)) {
            throw new IllegalArgumentException("quantity unit must not be treated as currency");
        }
    }

    public String matchKey() {
        return String.join("/", nullToEmpty(warehouseId), nullToEmpty(ownerId), skuId, nullToEmpty(businessLotKey));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
