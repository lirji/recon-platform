package com.lrj.recon.batch.ods;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 权益中心履约事实消费者；EXPECTED 明确丢弃，避免履约侧反向冒充营销应发。 */
@Component
@ConditionalOnProperty(name = "recon.ods.benefit.kafka-enabled", havingValue = "true")
public class BenefitOdsConsumer {
    private static final Set<String> NON_CASH_TYPES = Set.of(
            "COUPON", "SERVICE_VOUCHER", "REDEMPTION_CODE", "PHYSICAL");

    private final BenefitOdsIngestionService ingestion;
    private final ObjectMapper json;
    public BenefitOdsConsumer(BenefitOdsIngestionService ingestion, ObjectMapper json) {
        this.ingestion = ingestion; this.json = json;
    }

    /** 校验 envelope/payload 一致性后，把 INTERNAL 与 PROVIDER 分别落入独立 ODS 角色。 */
    @KafkaListener(topics = "${recon.ods.benefit.topic:benefit.fulfillment-event.v1}",
            groupId = "${recon.ods.benefit.group-id:recon-benefit-ods-v1}")
    public void consume(String payload) throws Exception {
        JsonNode envelope = json.readTree(payload);
        String schemaVersion = required(envelope, "schemaVersion");
        if (!(schemaVersion.equals("1") || schemaVersion.startsWith("1."))) {
            throw new IllegalArgumentException("unsupported benefit event schemaVersion: " + schemaVersion);
        }
        JsonNode fact = envelope.path("payload");
        String factType = required(fact, "factType");
        String eventType = required(envelope, "eventType");
        if (!eventType.equals("FULFILLMENT_" + factType)) {
            throw new IllegalArgumentException("fulfillment eventType does not match payload factType");
        }
        String benefitType = required(fact, "benefitType");
        boolean cash = "CASH".equals(benefitType);
        if (!cash && !NON_CASH_TYPES.contains(benefitType)) {
            throw new IllegalArgumentException("unsupported fulfillment benefitType: " + benefitType);
        }
        BenefitOdsEvent.FactType target = target(cash, factType);
        if (target == null) return; // 应发只信任 marketing.award-expected.v1，履约 EXPECTED 不冒充应发。
        String orderNo = required(fact, "awardOrderNo");
        String sourceRequestId = text(fact, "sourceRequestId");
        String issueId = text(fact, "clientItemId");
        if (issueId == null) issueId = required(fact, "awardItemNo"); // 兼容旧 v1 事件
        ingestion.ingestFulfillment(new BenefitOdsEvent(
                required(envelope, "tenantId"), required(envelope, "eventId"), target,
                issueId, cash && sourceRequestId != null ? sourceRequestId : orderNo,
                text(fact, "providerReference"),
                cash ? text(fact, "currency") : null,
                cash && fact.hasNonNull("amountMinor") ? fact.path("amountMinor").longValue() : null,
                text(fact, "entryType"), cash ? null : required(fact, "skuId"),
                cash ? null : fact.path("quantity").longValue(), normalizedStatus(target, text(fact, "status")),
                text(fact, "providerReference"), java.time.Instant.parse(required(envelope, "occurredAt")),
                null, issueId, null, null, rawRef(orderNo, sourceRequestId),
                null, sourceRequestId, orderNo));
    }

    /**
     * 通用金额引擎只保留 rawRef，因此把两项稳定关联键编码成可逆 token；不使用 SKU/时间近似推断。
     */
    private static String rawRef(String orderNo, String sourceRequestId) {
        String reference = "benefit-fulfillment:" + orderNo;
        return sourceRequestId == null || sourceRequestId.isBlank()
                ? reference : reference + "|marketing-award-expected:" + sourceRequestId;
    }

    private static String normalizedStatus(BenefitOdsEvent.FactType target, String status) {
        if (target == BenefitOdsEvent.FactType.ENTITLEMENT_EXPECTED) return "EXPECTED";
        if (status == null) return null;
        return switch (status) {
            case "SUCCEEDED", "ISSUED", "USED" -> "ISSUED";
            case "REVERSED" -> "REVERSED";
            case "FAILED_FINAL", "FINAL_FAILURE", "NOT_ISSUED", "FAILED" -> "FAILED";
            case "UNKNOWN", "RETRYABLE_FAILURE", "DISPATCHING", "QUERYING" -> "UNKNOWN";
            default -> status;
        };
    }

    private static BenefitOdsEvent.FactType target(boolean cash, String factType) {
        if (cash) return switch (factType) {
            case "EXPECTED" -> null;
            case "INTERNAL" -> BenefitOdsEvent.FactType.CASH_ACCOUNTING;
            case "PROVIDER" -> BenefitOdsEvent.FactType.CASH_CHANNEL;
            default -> throw new IllegalArgumentException("unsupported cash factType: " + factType);
        };
        return switch (factType) {
            case "EXPECTED" -> null;
            case "INTERNAL" -> BenefitOdsEvent.FactType.ENTITLEMENT_INTERNAL;
            case "PROVIDER" -> BenefitOdsEvent.FactType.ENTITLEMENT_PROVIDER;
            default -> throw new IllegalArgumentException("unsupported entitlement factType: " + factType);
        };
    }

    private static String required(JsonNode value, String field) {
        String result = text(value, field);
        if (result == null || result.isBlank()) throw new IllegalArgumentException("event field is required: " + field);
        return result;
    }

    private static String text(JsonNode value, String field) {
        return value.hasNonNull(field) ? value.path(field).asText() : null;
    }
}
