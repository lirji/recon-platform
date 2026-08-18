package com.lrj.recon.batch.job;

import com.lrj.recon.core.domain.service.Bucketing;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 端到端三方对账 (设计验收 §12, 里程碑 M4): seed 营销/账务/渠道三方 → 跑双段 {@code marketingThreeWayJob} →
 * SEG1 校验发放一致性 (营销↔账务, refine issue→order 1:N)、SEG2 校验资金一致性 (账务↔渠道, identity serial)。
 * 各段差异分类正确、各段 {@code recon_report} 双向守恒 balanced、bridge_break_stage 精确归因。
 */
class MarketingThreeWayEndToEndTest extends AbstractThreeWayJobIT {

    @Test
    void dualSegmentReconciliationClassifiesPerSegmentAndConservesIndependently() throws Exception {
        String runId = "run-3way-mixed";

        // ---- 干净 1:N: 发放单 O1 下两 issue (I1a 1000, I1b 2000), 各有渠道结算 ----
        marketing("m-I1a", "O1", "I1a", "USD", 1000, "ISSUE", "PAID");
        marketing("m-I1b", "O1", "I1b", "USD", 2000, "ISSUE", "PAID");
        accounting("a-I1a", "O1", "I1a", "C1a", "USD", 1000, "ISSUE", "PAID");
        accounting("a-I1b", "O1", "I1b", "C1b", "USD", 2000, "ISSUE", "PAID");
        channel("ch-C1a", "C1a", "USD", 1000, "ISSUE", "PAID");
        channel("ch-C1b", "C1b", "USD", 2000, "ISSUE", "PAID");

        // ---- SEG1 AMOUNT_MISMATCH: 营销 1000 vs 账务 900 (SEG2 侧账务900 vs 渠道900 干净) ----
        marketing("m-I2", "O2", "I2", "USD", 1000, "ISSUE", "PAID");
        accounting("a-I2", "O2", "I2", "C2", "USD", 900, "ISSUE", "PAID");
        channel("ch-C2", "C2", "USD", 900, "ISSUE", "PAID");

        // ---- SEG1 BRIDGE_BROKEN(SEG1): 账务 spine 缺发放ID I3 (仅营销) ----
        marketing("m-I3", "O3", "I3", "USD", 500, "ISSUE", "PAID");

        // ---- SEG1 GROUP_SUM_MISMATCH: 营销红蓝字 (400 ISSUE - 100 REFUND = 300) vs 账务 500 ----
        marketing("m-I5a", "O5", "I5", "USD", 400, "ISSUE", "PAID");
        marketing("m-I5b", "O5", "I5", "USD", -100, "REFUND", "PAID");
        accounting("a-I5", "O5", "I5", "C5", "USD", 500, "ISSUE", "PAID");
        channel("ch-C5", "C5", "USD", 500, "ISSUE", "PAID");

        // ---- SEG2 AMOUNT_MISMATCH: 账务 800 vs 渠道 700 (SEG1 侧营销800 vs 账务800 干净) ----
        marketing("m-I6", "O6", "I6", "USD", 800, "ISSUE", "PAID");
        accounting("a-I6", "O6", "I6", "C6", "USD", 800, "ISSUE", "PAID");
        channel("ch-C6", "C6", "USD", 700, "ISSUE", "PAID");

        // ---- SEG2 MISSING: 账务有渠道流水号 C8 但渠道未结算 (SEG1 侧 I8 干净) ----
        marketing("m-I8", "O8", "I8", "USD", 300, "ISSUE", "PAID");
        accounting("a-I8", "O8", "I8", "C8", "USD", 300, "ISSUE", "PAID");

        // ---- SEG2 BRIDGE_BROKEN(SEG2): 渠道有流水号 C9 但账务 spine 缺 ----
        channel("ch-C9", "C9", "USD", 700, "ISSUE", "PAID");

        JobExecution exec = launch(runId, 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // ---- SEG1 差异分类 (发放一致性): AMOUNT_MISMATCH(I2) + BRIDGE_BROKEN(I3) + GROUP_SUM(I5) ----
        assertThat(discrepancyTypes(runId, SEG1)).containsExactlyInAnyOrder(
                "AMOUNT_MISMATCH", "BRIDGE_BROKEN", "GROUP_SUM_MISMATCH");
        // ---- SEG2 差异分类 (资金一致性): AMOUNT_MISMATCH(C6) + MISSING(C8) + BRIDGE_BROKEN(C9) ----
        assertThat(discrepancyTypes(runId, SEG2)).containsExactlyInAnyOrder(
                "AMOUNT_MISMATCH", "MISSING", "BRIDGE_BROKEN");

        // ---- bridge_break_stage 精确归因: SEG1 断在 SEG1, SEG2 断在 SEG2 ----
        assertThat(jdbc.queryForObject(
                "SELECT bridge_break_stage FROM discrepancy WHERE run_id=? AND segment_id=? AND type='BRIDGE_BROKEN'",
                String.class, runId, SEG1)).isEqualTo("SEG1");
        assertThat(jdbc.queryForObject(
                "SELECT bridge_break_stage FROM discrepancy WHERE run_id=? AND segment_id=? AND type='BRIDGE_BROKEN'",
                String.class, runId, SEG2)).isEqualTo("SEG2");

        // ---- 各段 recon_report 双向守恒 balanced (SEG1 USD + SEG2 USD, residual=0) ----
        assertThat(count("recon_report", runId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=1", Long.class, runId)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT left_residual_minor + right_residual_minor AS r FROM recon_report WHERE run_id=?",
                Long.class, runId)).allMatch(r -> r == 0L);

        // ---- SEG1 权威侧 (营销应发) expected_total = 5900, matched(干净左额) = 4100 ----
        assertThat(reportLong(runId, SEG1, "expected_total_minor")).isEqualTo(5900L);
        assertThat(reportLong(runId, SEG1, "matched_amount_minor")).isEqualTo(4100L);
        assertThat(reportLong(runId, SEG1, "bridge_broken_minor")).isEqualTo(500L);   // I3
        // ---- SEG2 权威侧 (账务实发) expected_total = 5500, matched = 4400 ----
        assertThat(reportLong(runId, SEG2, "expected_total_minor")).isEqualTo(5500L);
        assertThat(reportLong(runId, SEG2, "matched_amount_minor")).isEqualTo(4400L);
        assertThat(reportLong(runId, SEG2, "missing_minor")).isEqualTo(300L);         // C8
        assertThat(reportLong(runId, SEG2, "bridge_broken_minor")).isEqualTo(700L);   // C9

        // ---- refine 1:N: 发放单 O1 的两 issue 落同一桶 (桶键 = group_key = 发放单号), 且 SEG1 中 match != group ----
        List<Integer> o1Buckets = jdbc.queryForList(
                "SELECT DISTINCT bucket FROM recon_record WHERE run_id=? AND segment_id=? AND group_key='O1'",
                Integer.class, runId, SEG1);
        assertThat(o1Buckets).containsExactly(Bucketing.bucketOf("O1", BUCKET_COUNT));
        // 该桶含 I1a/I1b 两 issue 的营销+账务共 4 条 (match_key=issue_id != group_key=order_no)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_record WHERE run_id=? AND segment_id=? AND group_key='O1'"
                        + " AND match_key <> group_key", Long.class, runId, SEG1)).isEqualTo(4L);

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void cleanThreeWayYieldsZeroDiscrepanciesAndBalanced() throws Exception {
        String runId = "run-3way-clean";
        marketing("m-1", "O1", "I1", "USD", 1000, "ISSUE", "PAID");
        accounting("a-1", "O1", "I1", "C1", "USD", 1000, "ISSUE", "PAID");
        channel("ch-1", "C1", "USD", 1000, "ISSUE", "PAID");

        JobExecution exec = launch(runId, 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(count("discrepancy", runId)).isZero();
        assertThat(count("recon_report", runId)).isEqualTo(2); // SEG1 USD + SEG2 USD
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=1", Long.class, runId)).isEqualTo(2);
        assertThat(reportLong(runId, SEG1, "matched_amount_minor")).isEqualTo(1000L);
        assertThat(reportLong(runId, SEG2, "matched_amount_minor")).isEqualTo(1000L);
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    private long reportLong(String runId, String segmentId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM recon_report WHERE run_id=? AND segment_id=? AND currency='USD'",
                Long.class, runId, segmentId);
    }
}
