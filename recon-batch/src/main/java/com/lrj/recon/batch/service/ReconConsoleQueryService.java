package com.lrj.recon.batch.service;

import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.DispositionStatus;
import com.lrj.recon.core.domain.model.ReconRunStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 管理台查询参数校验与只读编排。 */
@Service
public class ReconConsoleQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = 1_000_000;
    private static final Set<String> RUN_STATUSES = enumNames(ReconRunStatus.values());
    private static final Set<String> DISCREPANCY_TYPES = enumNames(DiscrepancyType.values());
    private static final Set<String> DISPOSITION_STATUSES = dispositionStatuses();

    private final ReconConsoleQueryRepository repository;

    public ReconConsoleQueryService(ReconConsoleQueryRepository repository) {
        this.repository = repository;
    }

    public ReconConsoleQueryRepository.DashboardView dashboard() {
        return repository.dashboard();
    }

    public ReconConsoleQueryRepository.PageResult<ReconConsoleQueryRepository.RunSummary> listRuns(
            String scenarioCode, String accountingPeriod, String status, Integer page, Integer size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        return repository.listRuns(new ReconConsoleQueryRepository.RunFilter(
                text(scenarioCode, "scenarioCode", 64),
                period(accountingPeriod),
                enumValue(status, "status", RUN_STATUSES),
                normalizedPage,
                normalizedSize));
    }

    public ReconConsoleQueryRepository.RunDetail getRun(String runId) {
        String id = requiredText(runId, "runId", 64);
        return repository.findRunDetail(id).orElseThrow(() -> new NotFoundException("run not found: " + id));
    }

    public ReconConsoleQueryRepository.PageResult<ReconConsoleQueryRepository.DiscrepancySummary> listDiscrepancies(
            String runId, String type, String status, String segmentId, String currency, String query,
            Integer page, Integer size) {
        String normalizedCurrency = text(currency, "currency", 3);
        if (normalizedCurrency != null) {
            normalizedCurrency = normalizedCurrency.toUpperCase(Locale.ROOT);
            if (!normalizedCurrency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency must be a 3-letter code");
            }
        }
        return repository.listDiscrepancies(new ReconConsoleQueryRepository.DiscrepancyFilter(
                text(runId, "runId", 64),
                enumValue(type, "type", DISCREPANCY_TYPES),
                enumValue(status, "status", DISPOSITION_STATUSES),
                text(segmentId, "segmentId", 32),
                normalizedCurrency,
                text(query, "query", 128),
                normalizePage(page),
                normalizeSize(size)));
    }

    public ReconConsoleQueryRepository.DiscrepancyDetail getDiscrepancy(String discrepancyId) {
        String id = requiredText(discrepancyId, "discrepancyId", 64);
        return repository.findDiscrepancy(id)
                .orElseThrow(() -> new NotFoundException("discrepancy not found: " + id));
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

    private static String period(String value) {
        String normalized = text(value, "accountingPeriod", 16);
        if (normalized == null) {
            return null;
        }
        try {
            LocalDate.parse(normalized);
            return normalized;
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("accountingPeriod must be ISO date YYYY-MM-DD", invalid);
        }
    }

    private static String enumValue(String value, String field, Set<String> allowed) {
        String normalized = text(value, field, 32);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + " must be one of " + allowed);
        }
        return normalized;
    }

    private static String requiredText(String value, String field, int maxLength) {
        String normalized = text(value, field, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static Set<String> dispositionStatuses() {
        Set<String> values = Arrays.stream(DispositionStatus.values()).map(Enum::name)
                .collect(Collectors.toSet());
        values.add("OPEN");
        return Set.copyOf(values);
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
