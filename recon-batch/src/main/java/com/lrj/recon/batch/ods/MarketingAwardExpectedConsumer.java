package com.lrj.recon.batch.ods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * 营销应发事实消费者。应发必须来自营销入队事务，不允许用权益履约 EXPECTED 反推。
 */
@Component
@ConditionalOnProperty(name = "recon.ods.benefit.kafka-enabled", havingValue = "true")
public class MarketingAwardExpectedConsumer {

    private static final String EXPECTED_SOURCE_SYSTEM = "MARKETING";
    private static final Set<String> NON_CASH_TYPES = Set.of(
            "COUPON", "SERVICE_VOUCHER", "REDEMPTION_CODE", "PHYSICAL");

    private final BenefitOdsIngestionService ingestion;
    private final ObjectMapper json;

    public MarketingAwardExpectedConsumer(BenefitOdsIngestionService ingestion, ObjectMapper json) {
        this.ingestion = ingestion;
        this.json = json;
    }

    /** 按 benefitType 分流金额/数量模型；字段与类型不一致时拒绝，不用字段缺失猜业务类型。 */
    @KafkaListener(topics = "${recon.ods.benefit.expected-topic:marketing.award-expected.v1}",
            groupId = "${recon.ods.benefit.expected-group-id:recon-marketing-award-expected-ods-v1}")
    public void consume(String payload) throws Exception {
        JsonNode event = json.readTree(payload);
        String schemaVersion = required(event, "schemaVersion");
        if (!(schemaVersion.equals("1") || schemaVersion.startsWith("1."))) {
            throw new IllegalArgumentException("unsupported marketing expected schemaVersion: " + schemaVersion);
        }
        if (!"MARKETING_AWARD_EXPECTED".equals(required(event, "eventType"))) {
            throw new IllegalArgumentException("unsupported marketing expected eventType");
        }
        String benefitType = required(event, "benefitType");
        boolean cash = "CASH".equals(benefitType);
        if (!cash && !NON_CASH_TYPES.contains(benefitType)) {
            throw new IllegalArgumentException("unsupported marketing expected benefitType: " + benefitType);
        }
        if (!cash && (event.hasNonNull("amountMinor") || event.hasNonNull("currency"))) {
            throw new IllegalArgumentException("non-cash marketing expected must not carry money");
        }
        if (cash && !event.hasNonNull("amountMinor")) {
            throw new IllegalArgumentException("cash marketing expected requires amountMinor");
        }
        String currency = cash ? required(event, "currency") : null;
        String sourceRequestId = required(event, "sourceRequestId");
        String eventId = required(event, "eventId");
        ingestion.ingestMarketingExpected(new BenefitOdsEvent(
                required(event, "tenantId"), eventId,
                cash ? BenefitOdsEvent.FactType.CASH_EXPECTED : BenefitOdsEvent.FactType.ENTITLEMENT_EXPECTED,
                required(event, "clientItemId"), cash ? sourceRequestId : null, null, currency,
                cash ? event.path("amountMinor").longValue() : null, "ISSUE",
                cash ? null : required(event, "skuId"), cash ? null : event.path("quantity").longValue(),
                cash ? null : "EXPECTED", null, Instant.parse(required(event, "occurredAt")),
                null, required(event, "clientItemId"), null, null,
                "marketing-award-expected:" + sourceRequestId, EXPECTED_SOURCE_SYSTEM, sourceRequestId, null));
    }

    private static String required(JsonNode value, String field) {
        String result = value.hasNonNull(field) ? value.path(field).asText() : null;
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("event field is required: " + field);
        }
        return result;
    }
}
