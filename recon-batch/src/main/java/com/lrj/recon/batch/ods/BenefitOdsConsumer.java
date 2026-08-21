package com.lrj.recon.batch.ods;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "recon.ods.benefit.kafka-enabled", havingValue = "true")
public class BenefitOdsConsumer {
    private final BenefitOdsIngestionService ingestion;
    private final ObjectMapper json;
    public BenefitOdsConsumer(BenefitOdsIngestionService ingestion, ObjectMapper json) {
        this.ingestion = ingestion; this.json = json;
    }

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
        boolean cash = "CASH".equals(required(fact, "benefitType"));
        BenefitOdsEvent.FactType target = target(cash, factType);
        if (target == null) return; // cash obligation becomes recon expected only after the internal ISSUE ledger exists
        ingestion.ingest(new BenefitOdsEvent(
                required(envelope, "tenantId"), required(envelope, "eventId"), target,
                required(fact, "awardItemNo"), required(fact, "awardOrderNo"), text(fact, "providerReference"),
                cash ? text(fact, "currency") : null,
                cash && fact.hasNonNull("amountMinor") ? fact.path("amountMinor").longValue() : null,
                text(fact, "entryType"), cash ? null : required(fact, "skuId"),
                cash ? null : fact.path("quantity").longValue(), normalizedStatus(target, text(fact, "status")),
                text(fact, "providerReference"), java.time.Instant.parse(required(envelope, "occurredAt")),
                null, required(fact, "awardItemNo"), null, null, "benefit-event:" + required(envelope, "eventId")));
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
            case "INTERNAL" -> BenefitOdsEvent.FactType.CASH_EXPECTED;
            case "PROVIDER" -> BenefitOdsEvent.FactType.CASH_CHANNEL;
            default -> throw new IllegalArgumentException("unsupported cash factType: " + factType);
        };
        return switch (factType) {
            case "EXPECTED" -> BenefitOdsEvent.FactType.ENTITLEMENT_EXPECTED;
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
