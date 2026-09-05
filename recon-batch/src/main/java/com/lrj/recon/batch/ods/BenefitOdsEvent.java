package com.lrj.recon.batch.ods;

import java.time.Instant;

/**
 * 两条上游事件流归一化后的 ODS 事实；金额与数量口径互斥，避免用占位币种伪装非现金权益。
 */
public record BenefitOdsEvent(
        String tenantId, String eventId, FactType factType, String issueId, String orderNo,
        String channelSerialNo, String currency, Long amountMinor, String entryType,
        String skuId, Long quantity, String fulfillmentStatus, String providerRef,
        Instant occurredAt, String cellId, String shardKey, Integer sourcePartition,
        Long sourceOffset, String rawRef, String expectedSourceSystem,
        String marketingSourceRequestId, String benefitOrderNo) {

    public BenefitOdsEvent {
        require("tenantId", tenantId); require("eventId", eventId); require("issueId", issueId);
        require("rawRef", rawRef);
        if (factType == null) throw new IllegalArgumentException("factType is required");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt is required");
        if (factType.monetary()) {
            if (factType != FactType.CASH_EXPECTED) require("orderNo", orderNo);
            require("currency", currency); require("entryType", entryType);
            if (!currency.matches("[A-Z]{3}") || amountMinor == null || amountMinor <= 0) {
                throw new IllegalArgumentException("cash fact requires positive money and uppercase ISO currency");
            }
            if (skuId != null || quantity != null) throw new IllegalArgumentException("cash fact cannot carry entitlement measure");
        } else {
            require("skuId", skuId); require("fulfillmentStatus", fulfillmentStatus);
            if (quantity == null || quantity <= 0) throw new IllegalArgumentException("entitlement quantity must be positive");
            if (currency != null || amountMinor != null) {
                throw new IllegalArgumentException("entitlement fact must not use synthetic currency/amount");
            }
        }
    }

    /** 兼容既有回放调用；新事件消费必须传入关联字段。 */
    public BenefitOdsEvent(String tenantId, String eventId, FactType factType, String issueId, String orderNo,
                           String channelSerialNo, String currency, Long amountMinor, String entryType,
                           String skuId, Long quantity, String fulfillmentStatus, String providerRef,
                           Instant occurredAt, String cellId, String shardKey, Integer sourcePartition,
                           Long sourceOffset, String rawRef) {
        this(tenantId, eventId, factType, issueId, orderNo, channelSerialNo, currency, amountMinor, entryType,
                skuId, quantity, fulfillmentStatus, providerRef, occurredAt, cellId, shardKey, sourcePartition,
                sourceOffset, rawRef, null, null, orderNo);
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    public enum FactType {
        CASH_EXPECTED(true, "recon_ods_cash_expected"),
        CASH_ACCOUNTING(true, "recon_ods_cash_accounting"),
        CASH_CHANNEL(true, "recon_ods_cash_channel"),
        ENTITLEMENT_EXPECTED(false, "recon_ods_entitlement_expected"),
        ENTITLEMENT_INTERNAL(false, "recon_ods_entitlement_internal"),
        ENTITLEMENT_PROVIDER(false, "recon_ods_entitlement_provider");

        private final boolean monetary;
        private final String table;
        FactType(boolean monetary, String table) { this.monetary = monetary; this.table = table; }
        public boolean monetary() { return monetary; }
        public String table() { return table; }
    }
}
