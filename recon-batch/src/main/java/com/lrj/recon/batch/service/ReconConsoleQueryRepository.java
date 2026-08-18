package com.lrj.recon.batch.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 管理台只读查询端口。
 *
 * <p>这些记录是面向运营界面的跨聚合投影，不属于 {@code recon-core} 领域不变量。JDBC 实现留在
 * {@code recon-batch.persistence}，避免查询 SQL 泄漏到 web/service 层。</p>
 */
public interface ReconConsoleQueryRepository {

    DashboardView dashboard();

    PageResult<RunSummary> listRuns(RunFilter filter);

    Optional<RunSummary> findRun(String runId);

    Optional<RunDetail> findRunDetail(String runId);

    PageResult<DiscrepancySummary> listDiscrepancies(DiscrepancyFilter filter);

    Optional<DiscrepancyDetail> findDiscrepancy(String discrepancyId);

    record RunFilter(String scenarioCode, String accountingPeriod, String status, int page, int size) {
    }

    record DiscrepancyFilter(String runId, String type, String status, String segmentId, String currency,
                             String query, int page, int size) {
    }

    record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public PageResult {
            content = List.copyOf(content);
        }

        public static <T> PageResult<T> of(List<T> content, int page, int size, long totalElements) {
            long pages = totalElements == 0 ? 0 : 1 + ((totalElements - 1) / size);
            int totalPages = pages > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pages;
            return new PageResult<>(content, page, size, totalElements, totalPages);
        }
    }

    record DashboardView(DashboardMetrics metrics, List<KeyCount> discrepancyTypes,
                         List<RunSummary> recentRuns) {
        public DashboardView {
            discrepancyTypes = List.copyOf(discrepancyTypes);
            recentRuns = List.copyOf(recentRuns);
        }
    }

    record DashboardMetrics(long totalRuns, long runningRuns, long completedRuns, long failedRuns,
                            long imbalancedRuns, long openDiscrepancies, long resolvedDiscrepancies,
                            long closedDiscrepancies) {
    }

    record KeyCount(String key, long count) {
    }

    record RunSummary(String runId, String scenarioCode, String accountingPeriod, int sequenceNo,
                      String status, int bucketCount, Instant createdAt, Instant startedAt, Instant finishedAt,
                      long discrepancyCount, long openDiscrepancyCount, Boolean balanced) {
    }

    record RunDetail(RunSummary run, List<ReportEntry> reports) {
        public RunDetail {
            reports = List.copyOf(reports);
        }
    }

    /** 金额使用十进制字符串，避免 JavaScript Number 丢失 BIGINT 精度。 */
    record ReportEntry(String segmentId, String currency, String expectedTotalMinor, String matchedAmountMinor,
                       String amountMismatchMinor, String missingMinor, String duplicateMinor, String extraMinor,
                       String timingMinor, String statusMismatchMinor, String currencyMismatchMinor,
                       String groupSumMismatchMinor, String bridgeBrokenMinor, String rightSideTotalMinor,
                       String leftResidualMinor, String rightResidualMinor, boolean balanced) {
    }

    record DiscrepancySummary(String discrepancyId, String runId, String scenarioCode, String accountingPeriod,
                              String segmentId, String type, String bridgeBreakStage, String fingerprint,
                              String groupKey, String matchKey, String currency, String expectedAmountMinor,
                              String actualAmountMinor, String deltaAmountMinor, String leftRawRef, String rightRawRef,
                              String dispositionStatus, String operator, String note, Integer dispositionVersion,
                              Instant createdAt, Instant updatedAt) {
    }

    record DiscrepancyDetail(DiscrepancySummary discrepancy, List<ActionEntry> actions,
                             List<ReversalEntry> reversals, List<AlertEntry> alerts) {
        public DiscrepancyDetail {
            actions = List.copyOf(actions);
            reversals = List.copyOf(reversals);
            alerts = List.copyOf(alerts);
        }
    }

    record ActionEntry(String id, String actionType, String payload, String operator, Instant createdAt) {
    }

    record ReversalEntry(String id, String runId, String groupKey, String suggestedAmountMinor,
                         String currency, String status, String operator, Instant createdAt) {
    }

    record AlertEntry(String id, String runId, String status, int attempt, Instant createdAt, Instant sentAt) {
    }
}
