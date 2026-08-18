package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.application.port.out.ReconReportRepository;
import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.ReconReport;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.ClassifiedGroup;
import com.lrj.recon.core.domain.service.ConservationChecker;
import com.lrj.recon.core.domain.service.DiscrepancyClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 验收: <b>分桶并行端到端 与 M2 单线程等价</b> (设计 §11 M3):
 * <ol>
 *   <li>并行跑 (多 bucket), 持久化的 recon_report 与对同一 staging 跑 M2 双遍 {@link ConservationChecker}
 *       基线<b>逐字段相等</b>; 差异分类正确;</li>
 *   <li>同一 seed 两种模式 (bucketCount=1 串行 vs 16 并行) 差异集与报表<b>完全一致</b>。</li>
 * </ol>
 */
class ReconJobPartitionedParityTest extends AbstractReconJobIT {

    private static final String SEG = "SEG1_MKT_ACCT";

    @Autowired ReconRecordRepository records;
    @Autowired ReconReportRepository reportRepo;

    /** 铺满多个 bucket 的混合数据集 (与 e2e 同构 + 更多干净匹配)。 */
    private void seedMixed() {
        for (int i = 0; i < 30; i++) {                     // 干净匹配, issue_id 散布多个 bucket
            marketing("m-ok-" + i, "I-OK-" + i, "USD", 100 + i, "ISSUE", "PAID", BIZ);
            accounting("a-ok-" + i, "I-OK-" + i, "USD", 100 + i, "ISSUE", "PAID", BIZ);
        }
        marketing("m-amt", "I-AMT", "USD", 1000, "ISSUE", "PAID", BIZ);   // AMOUNT_MISMATCH
        accounting("a-amt", "I-AMT", "USD", 900, "ISSUE", "PAID", BIZ);
        marketing("m-miss", "I-MISS", "USD", 500, "ISSUE", "PAID", BIZ);  // MISSING
        accounting("a-extra", "I-EXTRA", "USD", 700, "ISSUE", "PAID", BIZ); // EXTRA
        marketing("m-dup-1", "I-DUP", "USD", 300, "ISSUE", "PAID", BIZ);  // DUPLICATE
        marketing("m-dup-2", "I-DUP", "USD", 300, "ISSUE", "PAID", BIZ);
        accounting("a-dup", "I-DUP", "USD", 600, "ISSUE", "PAID", BIZ);
        marketing("m-gsm-1", "I-GSM", "USD", 400, "ISSUE", "PAID", BIZ);  // GROUP_SUM_MISMATCH (红蓝字)
        marketing("m-gsm-2", "I-GSM", "USD", -100, "REFUND", "PAID", BIZ);
        accounting("a-gsm", "I-GSM", "USD", 500, "ISSUE", "PAID", BIZ);
        marketing("m-stat", "I-STAT", "USD", 200, "ISSUE", "PAID", BIZ);  // STATUS_MISMATCH
        accounting("a-stat", "I-STAT", "USD", 200, "ISSUE", "PENDING", BIZ);
        marketing("m-ccy", "I-CCY", "USD", 200, "ISSUE", "PAID", BIZ);    // CURRENCY_MISMATCH (跨 USD/EUR)
        accounting("a-ccy", "I-CCY", "EUR", 200, "ISSUE", "PAID", BIZ);
    }

    @Test
    void parallel_report_equals_double_pass_baseline_and_classifies_correctly() throws Exception {
        String runId = "run-par-parity";
        seedMixed();

        JobExecution exec = launch(runId, 1, 16);   // 16 bucket 并行
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 差异分类: 7 类各一 (30 条干净匹配无差)
        assertThat(discrepancyTypes(runId)).containsExactlyInAnyOrder(
                "AMOUNT_MISMATCH", "MISSING", "EXTRA", "DUPLICATE",
                "GROUP_SUM_MISMATCH", "STATUS_MISMATCH", "CURRENCY_MISMATCH");

        // 单遍并行持久化的报表 == 对同一 staging 跑 M2 双遍守恒基线 (逐字段)
        Map<String, ReconReport> persisted = indexByCcy(reportRepo.listByRun(runId));
        Map<String, ReconReport> baseline = indexByCcy(doublePassBaseline(runId));
        assertThat(persisted.keySet()).isEqualTo(baseline.keySet());
        assertThat(persisted.keySet()).containsExactlyInAnyOrder("USD", "EUR");
        for (String ccy : baseline.keySet()) {
            assertReportsEqual(persisted.get(ccy), baseline.get(ccy));
            assertThat(persisted.get(ccy).balanced()).isTrue();
        }
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void serial_and_parallel_modes_yield_identical_results() throws Exception {
        seedMixed();

        // 模式 A: 串行 (1 bucket)。捕获结果后清 run 级表 (record_id = 表:主键 与 Run 无关, 同源两 Run 会撞 PK)。
        JobExecution serial = launch("run-mode-serial", 1, 1);
        assertThat(serial.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<String> serialTypes = discrepancyTypes("run-mode-serial");
        Map<String, ReconReport> s = indexByCcy(reportRepo.listByRun("run-mode-serial"));
        clearRunScopedTables();

        // 模式 B: 并行 (16 bucket), 同一 seed。
        JobExecution parallel = launch("run-mode-parallel", 1, 16);
        assertThat(parallel.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<String> parallelTypes = discrepancyTypes("run-mode-parallel");
        Map<String, ReconReport> p = indexByCcy(reportRepo.listByRun("run-mode-parallel"));

        // 差异类型多集一致 + 每个 (currency) 报表逐字段一致 → 两种模式结果完全相同
        assertThat(serialTypes).isEqualTo(parallelTypes);
        assertThat(s.keySet()).isEqualTo(p.keySet());
        for (String ccy : s.keySet()) {
            assertReportsEqual(s.get(ccy), p.get(ccy));
        }
    }

    /** 清 Run 级机器产物 (保源表/人工表), 供同源多模式对照 (record_id 与 Run 无关, 须清后重跑另一模式)。 */
    private void clearRunScopedTables() {
        for (String t : List.of("recon_record", "recon_record_reject", "discrepancy",
                "recon_report_partial", "recon_report", "recon_run", "recon_run_seq", "alert_outbox")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    /** M2 双遍基线: 段级归并 → 分类 → ConservationChecker (与 M2 reportStep 旧算法同构)。 */
    private List<ReconReport> doublePassBaseline(String runId) {
        EvaluationContext ctx = EvaluationContext.builder()
                .runId(runId).scenarioCode(SCENARIO).accountingPeriod(PERIOD).segmentId(SEG)
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(null)
                .matchWindowFrom(WINDOW_FROM).matchWindowTo(WINDOW_TO)
                .build();
        DiscrepancyClassifier classifier = new DiscrepancyClassifier();
        List<ClassifiedGroup> classified = new ArrayList<>();
        try (SegmentGroupCursor cursor = new SegmentGroupCursor(records, runId, SEG)) {
            MatchGroup g;
            while ((g = cursor.next()) != null) {
                Discrepancy d = classifier.classify(g, ctx);
                classified.add(d == null ? ClassifiedGroup.matched(g) : ClassifiedGroup.of(g, d.type()));
            }
        }
        return new ConservationChecker().check(runId, SEG, classified);
    }

    private static Map<String, ReconReport> indexByCcy(List<ReconReport> reports) {
        return reports.stream().collect(Collectors.toMap(ReconReport::currency, Function.identity()));
    }

    private static void assertReportsEqual(ReconReport a, ReconReport b) {
        assertThat(a.expectedTotalMinor()).isEqualTo(b.expectedTotalMinor());
        assertThat(a.matchedAmountMinor()).isEqualTo(b.matchedAmountMinor());
        assertThat(a.amountMismatchMinor()).isEqualTo(b.amountMismatchMinor());
        assertThat(a.missingMinor()).isEqualTo(b.missingMinor());
        assertThat(a.duplicateMinor()).isEqualTo(b.duplicateMinor());
        assertThat(a.extraMinor()).isEqualTo(b.extraMinor());
        assertThat(a.timingMinor()).isEqualTo(b.timingMinor());
        assertThat(a.statusMismatchMinor()).isEqualTo(b.statusMismatchMinor());
        assertThat(a.currencyMismatchMinor()).isEqualTo(b.currencyMismatchMinor());
        assertThat(a.groupSumMismatchMinor()).isEqualTo(b.groupSumMismatchMinor());
        assertThat(a.bridgeBrokenMinor()).isEqualTo(b.bridgeBrokenMinor());
        assertThat(a.rightSideTotalMinor()).isEqualTo(b.rightSideTotalMinor());
        assertThat(a.leftResidualMinor()).isEqualTo(b.leftResidualMinor());
        assertThat(a.rightResidualMinor()).isEqualTo(b.rightResidualMinor());
        assertThat(a.balanced()).isEqualTo(b.balanced());
    }

    private String runStatus(String runId) {
        return jdbc.queryForObject("SELECT status FROM recon_run WHERE run_id=?", String.class, runId);
    }
}
