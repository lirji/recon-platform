package com.lrj.recon.batch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(name = "recon.remediation.result-consumer-enabled", havingValue = "true")
public class BenefitRemediationResultConsumer {
    private final ObjectMapper json;
    private final MessageInboxStore inbox;
    private final BenefitRemediationService service;
    private final String consumerGroup;

    public BenefitRemediationResultConsumer(ObjectMapper json, MessageInboxStore inbox,
                                             BenefitRemediationService service,
                                             @Value("${recon.remediation.result-group:recon-benefit-remediation-result-v1}")
                                             String consumerGroup) {
        this.json = json; this.inbox = inbox; this.service = service; this.consumerGroup = consumerGroup;
    }

    @KafkaListener(topics = "${recon.remediation.result-topic:benefit.remediation.result.v1}",
            groupId = "${recon.remediation.result-group:recon-benefit-remediation-result-v1}")
    @Transactional
    public void consume(String raw) throws Exception {
        JsonNode envelope = json.readTree(raw);
        String version = required(envelope, "schemaVersion");
        if (!(version.equals("1") || version.startsWith("1."))) {
            throw new IllegalArgumentException("unsupported remediation result schemaVersion: " + version);
        }
        String eventType = required(envelope, "eventType");
        if (!eventType.equals("REMEDIATION_DISPATCHED") && !eventType.equals("REMEDIATION_RESULT")) {
            throw new IllegalArgumentException("unsupported remediation result eventType: " + eventType);
        }
        String tenantId = required(envelope, "tenantId");
        String eventId = required(envelope, "eventId");
        String hash = sha256(raw);
        if (inbox.claim(tenantId, consumerGroup, eventId, hash) == MessageInboxStore.ClaimResult.REPLAY) {
            return;
        }
        if (eventType.equals("REMEDIATION_RESULT")) {
            JsonNode payload = envelope.path("payload");
            service.settle(tenantId, required(payload, "externalCommandId"), required(payload, "status"));
        }
        inbox.markProcessed(tenantId, consumerGroup, eventId);
    }

    private static String required(JsonNode node, String field) {
        String value = node.hasNonNull(field) ? node.path(field).asText() : null;
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing result field: " + field);
        return value;
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
