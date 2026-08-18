package com.lrj.recon.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 修复 D 回归 (MarketingThreeWayConfig 硬编码 exact() 使 ToleranceEvaluator 成死代码)。
 *
 * <p>本用例经 {@code @TestPropertySource} 把 SEG1 判差规则配为 <b>TOLERANCE</b> + 绝对阈值 100 (分), 跑双段
 * {@code marketingThreeWayJob}, 证明<b>运行链路真能命中 {@link com.lrj.recon.core.domain.service.ToleranceEvaluator}</b>:
 * <ul>
 *   <li>SEG1 内 {@code |expected-actual|<=100} 的金额差 (营销 1000 vs 账务 950, Δ=50) 被容差抹平 →
 *       <b>不产 AMOUNT_MISMATCH</b>, 按干净匹配入 matched (EXACT 下会产差, 故此点即证容差生效);</li>
 *   <li>容差外的差 (营销 2000 vs 账务 1800, Δ=200>100) 仍产 AMOUNT_MISMATCH。</li>
 * </ul>
 * SEG2 未配容差 (默认 EXACT), 两段守恒仍 balanced。
 */
@TestPropertySource(properties = {
        "recon.scenario.mkt.seg1.evaluator-type=TOLERANCE",
        "recon.scenario.mkt.seg1.abs-tolerance-minor=100"
})
class MarketingThreeWayToleranceConfigTest extends AbstractThreeWayJobIT {

    @Test
    void seg1ToleranceConfigSuppressesWithinToleranceMismatchButNotOutside() throws Exception {
        String runId = "run-3way-tolerance";

        // 容差内 (Δ=50 <= 100): SEG1 营销 1000 vs 账务 950 → 无 AMOUNT_MISMATCH; SEG2 账务 950 vs 渠道 950 干净。
        marketing("m-A", "O-A", "I-A", "USD", 1000, "ISSUE", "PAID");
        accounting("a-A", "O-A", "I-A", "C-A", "USD", 950, "ISSUE", "PAID");
        channel("ch-A", "C-A", "USD", 950, "ISSUE", "PAID");

        // 容差外 (Δ=200 > 100): SEG1 营销 2000 vs 账务 1800 → AMOUNT_MISMATCH; SEG2 账务 1800 vs 渠道 1800 干净。
        marketing("m-B", "O-B", "I-B", "USD", 2000, "ISSUE", "PAID");
        accounting("a-B", "O-B", "I-B", "C-B", "USD", 1800, "ISSUE", "PAID");
        channel("ch-B", "C-B", "USD", 1800, "ISSUE", "PAID");

        JobExecution exec = launch(runId, 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 关键: SEG1 只剩容差外那 1 条 AMOUNT_MISMATCH (EXACT 下会是 2 条) → ToleranceEvaluator 运行态确被命中。
        assertThat(discrepancyTypes(runId, SEG1)).containsExactly("AMOUNT_MISMATCH");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy WHERE run_id=? AND segment_id=? AND type='AMOUNT_MISMATCH'",
                Long.class, runId, SEG1)).isEqualTo(1L);

        // 容差内组按干净匹配入 matched: SEG1 matched=1000 (仅容差内左额), amount_mismatch=2000 (容差外左额)。
        assertThat(reportLong(runId, SEG1, "expected_total_minor")).isEqualTo(3000L);
        assertThat(reportLong(runId, SEG1, "matched_amount_minor")).isEqualTo(1000L);
        assertThat(reportLong(runId, SEG1, "amount_mismatch_minor")).isEqualTo(2000L);

        // SEG2 默认 EXACT 且两侧一致 → 干净; 两段守恒 balanced。
        assertThat(discrepancyTypes(runId, SEG2)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=1", Long.class, runId)).isEqualTo(2L);
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    private long reportLong(String runId, String segmentId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM recon_report WHERE run_id=? AND segment_id=? AND currency='USD'",
                Long.class, runId, segmentId);
    }
}
