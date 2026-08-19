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

    /**
     * A5 / KI-6 数据质量护栏:扫 staged {@code recon_record} 找违反<b>函数性 refine</b> 的 match_key ——
     * 同一 (segment, match_key) 映射到 &gt;1 个 group_key(如同一营销发放ID 左侧挂 Ga、右侧挂 Gb)。这类脏跨表数据
     * 会让两侧落<b>不同桶</b>而永不相遇 → 产假 BRIDGE_BROKEN/EXTRA,且左右额独立入各自口径,双向守恒仍 residual≡0
     * <b>抓不到</b>。DB 侧 {@code GROUP BY ... HAVING COUNT(DISTINCT group_key) > 1} 完成,不建 Java 全表映射、不占对账热路径。
     * 结果按冲突 group 数降序、有界({@code limit});{@code limit} 传 N+1 由上层判断是否被截断。
     */
    List<RefineViolation> findRefineViolations(String runId, int limit);

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

    /**
     * B1 三方合并 roll-up 摘要(阶段二)。由 {@link ReconConsoleQueryService#threeWayRollup} 从一个 Run 的两段
     * {@link ReportEntry}(SEG1 营销↔账务、SEG2 账务↔渠道)<b>派生</b>而来,不引入新 SQL/算法:
     * {@code threeWayBalanced} = 每币种两段皆 balanced(布尔与,非跨段金额求和,避免重复计 spine 账务侧)。
     */
    record ThreeWayReport(String runId, String scenarioCode, String accountingPeriod, String status,
                          Boolean threeWayBalanced, List<CurrencyRollup> currencies) {
        public ThreeWayReport {
            currencies = List.copyOf(currencies);
        }
    }

    /**
     * 单币种三方 roll-up。{@code seg1}/{@code seg2} 为该币种两段原始报表(缺段为 null → 链路不完整);
     * {@code threeWayConsistent} = 两段均在且均 balanced;{@code bridgeBrokenMinor} = 两段桥断额之和(两个
     * 不同断点阶段的独立金额,非重复计),是三方链路专有诊断。金额十进制字符串,防 BIGINT 精度损失。
     */
    record CurrencyRollup(String currency, ReportEntry seg1, ReportEntry seg2,
                          boolean threeWayConsistent, String bridgeBrokenMinor) {
    }

    /** KI-6 单条函数性 refine 违规:同一 (segment, match_key) 映射到多个 group_key(distinctGroupCount)。 */
    record RefineViolation(String segmentId, String matchKey, long distinctGroupCount) {
    }

    /**
     * KI-6 违规报告。{@code truncated}=是否被有界截断(还有更多违规未列出),遵循「不静默截断」原则。
     * {@code violationCount}=本次列出的条数。
     */
    record RefineViolationReport(String runId, int violationCount, boolean truncated,
                                 List<RefineViolation> violations) {
        public RefineViolationReport {
            violations = List.copyOf(violations);
        }
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
