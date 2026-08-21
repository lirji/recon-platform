package com.lrj.recon.batch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.recon.core.application.port.out.RemediationCommandOutboxRepository;
import com.lrj.recon.core.application.port.out.RemediationSuggestionRepository;
import com.lrj.recon.core.domain.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class BenefitRemediationService {
    private final RemediationSuggestionRepository suggestions;
    private final RemediationCommandOutboxRepository commands;
    private final ObjectMapper json;

    public BenefitRemediationService(RemediationSuggestionRepository suggestions,
                                     RemediationCommandOutboxRepository commands, ObjectMapper json) {
        this.suggestions = suggestions; this.commands = commands; this.json = json;
    }

    @Transactional
    public RemediationSuggestion propose(ProposeCommand command) {
        require("tenantId", command.tenantId());
        require("scenarioCode", command.scenarioCode());
        require("discrepancyRef", command.discrepancyRef());
        require("awardItemNo", command.awardItemNo());
        require("reason", command.reason());
        if (command.action() == null) throw new IllegalArgumentException("action is required");
        if (command.action() != RemediationAction.MANUAL_REVIEW
                && (command.originalOperationNo() == null || command.originalOperationNo().isBlank())) {
            throw new IllegalArgumentException("automatable remediation requires originalOperationNo");
        }
        var value = new RemediationSuggestion(command.tenantId(), UUID.randomUUID().toString(),
                command.scenarioCode(), command.discrepancyRef(), command.awardItemNo(),
                command.originalOperationNo(), command.action(), command.reason(), RemediationStatus.PROPOSED, 0);
        if (!suggestions.insert(value)) throw new IllegalStateException("remediation already proposed");
        return value;
    }

    @Transactional
    public RemediationSuggestion approve(String tenantId, String suggestionId, String approvalRef) {
        if (approvalRef == null || approvalRef.isBlank()) throw new IllegalArgumentException("approvalRef is required");
        RemediationSuggestion value = suggestions.find(tenantId, suggestionId).orElseThrow();
        if (value.action() == RemediationAction.MANUAL_REVIEW) {
            throw new IllegalStateException("manual review cannot emit an automatic command");
        }
        long version = value.version();
        value.approve();
        if (!suggestions.updateExpectedVersion(value, version, approvalRef)) throw new IllegalStateException("approval CAS failed");
        String commandId = "recon-remediation:" + suggestionId;
        Map<String, Object> payload = Map.of(
                "externalCommandId", commandId,
                "action", value.action().name(),
                "awardItemNo", value.awardItemNo(),
                "originalOperationNo", value.originalOperationNo(),
                "reason", value.reason(),
                "approvalRef", approvalRef);
        Instant now = Instant.now();
        Map<String, Object> envelope = Map.of(
                "eventId", commandId, "eventType", "REMEDIATION_COMMAND", "schemaVersion", "1.0",
                "tenantId", tenantId, "occurredAt", now.toString(), "traceId", suggestionId,
                "partitionKey", value.awardItemNo(), "payload", payload);
        try {
            String serialized = json.writeValueAsString(envelope);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serialized.getBytes(StandardCharsets.UTF_8)));
            commands.enqueue(tenantId, commandId, suggestionId, commandId, hash, serialized, now);
        } catch (Exception failure) { throw new IllegalStateException("cannot build remediation command", failure); }
        return value;
    }

    @Transactional
    public RemediationSuggestion reject(String tenantId, String suggestionId, String approvalRef) {
        RemediationSuggestion value = suggestions.find(tenantId, suggestionId).orElseThrow();
        long version = value.version(); value.reject();
        if (!suggestions.updateExpectedVersion(value, version, approvalRef)) throw new IllegalStateException("reject CAS failed");
        return value;
    }

    @Transactional
    public RemediationSuggestion settle(String tenantId, String externalCommandId, String resultStatus) {
        String suggestionId = commands.findSuggestionId(tenantId, externalCommandId)
                .orElseThrow(() -> new IllegalArgumentException("unknown remediation command"));
        RemediationSuggestion value = suggestions.find(tenantId, suggestionId).orElseThrow();
        RemediationStatus target = RemediationStatus.valueOf(resultStatus);
        if (value.status() == target) return value;
        if (value.status() == RemediationStatus.SUCCEEDED || value.status() == RemediationStatus.FAILED) {
            throw new IllegalStateException("terminal remediation result conflict");
        }
        long version = value.version();
        switch (target) {
            case SUCCEEDED -> value.succeed();
            case FAILED -> value.fail();
            case UNKNOWN -> value.unknown();
            default -> throw new IllegalArgumentException("unsupported remediation result status: " + resultStatus);
        }
        if (!suggestions.updateExpectedVersion(value, version, null)) {
            throw new IllegalStateException("remediation result CAS failed");
        }
        return value;
    }

    public record ProposeCommand(String tenantId, String scenarioCode, String discrepancyRef,
                                 String awardItemNo, String originalOperationNo,
                                 RemediationAction action, String reason) {}

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
