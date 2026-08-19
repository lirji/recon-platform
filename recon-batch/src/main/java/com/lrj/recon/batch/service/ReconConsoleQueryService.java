package com.lrj.recon.batch.service;

import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.DispositionStatus;
import com.lrj.recon.core.domain.model.ReconRunStatus;
import com.lrj.recon.scenario.MarketingThreeWayScenario;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 管理台查询参数校验与只读编排。 */
@Service
public class ReconConsoleQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = 1_000_000;
    /** KI-6 诊断有界上限:列出的违规条数上限,超出置 truncated(不静默截断)。 */
    private static final int MAX_REFINE_VIOLATIONS = 100;
    private static final int MAX_GROUP_RECORDS = 500;
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

    /**
     * B1 三方合并 roll-up 摘要:从一个 Run 的两段报表(SEG1 营销↔账务、SEG2 账务↔渠道)<b>派生</b>单一三方视图,
     * 纯读、无新 SQL、无算法风险。口径(设计 A2 将合并归阶段二未定细则,此处为本平台阶段二取定,标为显式约定):
     * <ul>
     *   <li>按币种分组;每币种取 SEG1/SEG2 原始报表(缺段 → 该币种链路不完整);</li>
     *   <li>{@code threeWayConsistent} = 两段均在且均 balanced(<b>布尔与</b>,不跨段求和金额 —— spine 账务侧被两段
     *       共享,相加会重复计;故只做状态合成,原始各段金额并列呈现供下钻);</li>
     *   <li>{@code bridgeBrokenMinor} = 两段桥断额之和(两个独立断点阶段,非重复计),三方链路专有诊断;</li>
     *   <li>{@code threeWayBalanced} = 所有币种皆 consistent(无报表 → null)。</li>
     * </ul>
     * 仅识别营销三方场景的两段(MVP 唯一三方场景);其它段忽略。
     */
    public ReconConsoleQueryRepository.ThreeWayReport threeWayRollup(String runId) {
        ReconConsoleQueryRepository.RunDetail detail = getRun(runId);
        ReconConsoleQueryRepository.RunSummary run = detail.run();

        Map<String, ReconConsoleQueryRepository.ReportEntry> seg1 = new HashMap<>();
        Map<String, ReconConsoleQueryRepository.ReportEntry> seg2 = new HashMap<>();
        for (ReconConsoleQueryRepository.ReportEntry entry : detail.reports()) {
            if (MarketingThreeWayScenario.SEG1.equals(entry.segmentId())) {
                seg1.put(entry.currency(), entry);
            } else if (MarketingThreeWayScenario.SEG2.equals(entry.segmentId())) {
                seg2.put(entry.currency(), entry);
            }
        }

        List<ReconConsoleQueryRepository.CurrencyRollup> currencies = Stream
                .concat(seg1.keySet().stream(), seg2.keySet().stream())
                .distinct()
                .sorted()
                .map(ccy -> {
                    ReconConsoleQueryRepository.ReportEntry s1 = seg1.get(ccy);
                    ReconConsoleQueryRepository.ReportEntry s2 = seg2.get(ccy);
                    boolean consistent = s1 != null && s2 != null && s1.balanced() && s2.balanced();
                    long bridge = Math.addExact(bridgeBrokenMinor(s1), bridgeBrokenMinor(s2));
                    return new ReconConsoleQueryRepository.CurrencyRollup(ccy, s1, s2, consistent, Long.toString(bridge));
                })
                .toList();

        Boolean threeWayBalanced = currencies.isEmpty()
                ? null
                : currencies.stream().allMatch(ReconConsoleQueryRepository.CurrencyRollup::threeWayConsistent);

        return new ReconConsoleQueryRepository.ThreeWayReport(
                run.runId(), run.scenarioCode(), run.accountingPeriod(), run.status(), threeWayBalanced, currencies);
    }

    /** 两个桥断额是不同断点阶段的独立金额, {@code addExact} 溢出 fail-fast(与 MoneyMath 一致); 缺段计 0。 */
    private static long bridgeBrokenMinor(ReconConsoleQueryRepository.ReportEntry entry) {
        return entry == null ? 0L : Long.parseLong(entry.bridgeBrokenMinor());
    }

    /**
     * A5 / KI-6 数据质量护栏:列出某 Run staged {@code recon_record} 中违反函数性 refine 的 match_key
     * (同一 (segment, match_key) 映射多个 group_key)。把「脏跨表数据产假 BRIDGE_BROKEN/EXTRA 而守恒抓不到」
     * 从隐性升级为<b>显式可发现</b>。只读、DB 侧聚合、不占对账热路径。有界 {@link #MAX_REFINE_VIOLATIONS},
     * 超出置 {@code truncated}(不静默截断)。
     */
    public ReconConsoleQueryRepository.RefineViolationReport refineViolations(String runId) {
        String id = requiredText(runId, "runId", 64);
        List<ReconConsoleQueryRepository.RefineViolation> found =
                repository.findRefineViolations(id, MAX_REFINE_VIOLATIONS + 1);
        boolean truncated = found.size() > MAX_REFINE_VIOLATIONS;
        List<ReconConsoleQueryRepository.RefineViolation> violations =
                truncated ? List.copyOf(found.subList(0, MAX_REFINE_VIOLATIONS)) : found;
        return new ReconConsoleQueryRepository.RefineViolationReport(id, violations.size(), truncated, violations);
    }

    /** B7 · 1:N 明细下钻:组(run+segment+group_key)底层 staged 记录明细,有界 {@link #MAX_GROUP_RECORDS}。 */
    public ReconConsoleQueryRepository.GroupRecordReport groupRecords(String runId, String segmentId, String groupKey) {
        String rid = requiredText(runId, "runId", 64);
        String seg = requiredText(segmentId, "segmentId", 32);
        String gk = requiredText(groupKey, "groupKey", 128);
        List<ReconConsoleQueryRepository.GroupRecordDetail> found =
                repository.findGroupRecords(rid, seg, gk, MAX_GROUP_RECORDS + 1);
        boolean truncated = found.size() > MAX_GROUP_RECORDS;
        List<ReconConsoleQueryRepository.GroupRecordDetail> records =
                truncated ? List.copyOf(found.subList(0, MAX_GROUP_RECORDS)) : found;
        return new ReconConsoleQueryRepository.GroupRecordReport(rid, seg, gk, records.size(), truncated, records);
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
