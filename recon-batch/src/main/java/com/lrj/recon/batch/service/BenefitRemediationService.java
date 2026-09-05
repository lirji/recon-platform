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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BenefitRemediationService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = 1_000_000;
    private static final Set<String> STATUSES = Arrays.stream(RemediationStatus.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final RemediationSuggestionRepository suggestions;
    private final RemediationCommandOutboxRepository commands;
    private final RemediationSuggestionQuery query;
    private final ObjectMapper json;

    public BenefitRemediationService(RemediationSuggestionRepository suggestions,
                                     RemediationCommandOutboxRepository commands,
                                     RemediationSuggestionQuery query,
                                     ObjectMapper json) {
        this.suggestions = suggestions;
        this.commands = commands;
        this.query = query;
        this.json = json;
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

    /**
     * 管理台列表:必填 tenantId,可选 status;page/size 与其它 console 查询同一上限。
     * 只读,不触发 outbox / 中台闭环。
     */
    public ReconConsoleQueryRepository.PageResult<RemediationSuggestionQuery.RemediationView> list(
            String tenantId, String status, Integer page, Integer size) {
        String tenant = requiredText(tenantId, "tenantId", 64);
        String normalizedStatus = enumValue(status);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        long total = query.count(tenant, normalizedStatus);
        List<RemediationSuggestionQuery.RemediationView> rows =
                query.list(tenant, normalizedStatus, normalizedSize, normalizedPage * normalizedSize);
        return ReconConsoleQueryRepository.PageResult.of(rows, normalizedPage, normalizedSize, total);
    }

    public record ProposeCommand(String tenantId, String scenarioCode, String discrepancyRef,
                                 String awardItemNo, String originalOperationNo,
                                 RemediationAction action, String reason) {}

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static String enumValue(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be one of " + STATUSES);
        }
        return normalized;
    }

    private static int normalizePage(Integer page) {
        int value = page == null ? 0 : page;
        if (value < 0 || value > MAX_PAGE) {
            throw new IllegalArgumentException("page must be between 0 and " + MAX_PAGE);
        }
        return value;
    }

    private static int normalizeSize(Integer size) {
        int value = size == null ? DEFAULT_PAGE_SIZE : size;
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return value;
    }
}
