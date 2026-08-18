package com.lrj.recon.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 端到端 (设计验收 §12): seed marketing/accounting → 跑 Job → 断言差异分类 + recon_report 双向守恒。
 */
class ReconJobEndToEndTest extends AbstractReconJobIT {

    @Test
    void mixedDataClassifiesDiscrepanciesAndConservationBalances() throws Exception {
        String runId = "run-e2e-mixed";

        // 干净匹配 (无差异)
        marketing("m-clean", "I-CLEAN", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-clean", "I-CLEAN", "USD", 1000, "ISSUE", "PAID", BIZ);

        // AMOUNT_MISMATCH: 1:1 金额不等
        marketing("m-amt", "I-AMT", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-amt", "I-AMT", "USD", 900, "ISSUE", "PAID", BIZ);

        // MISSING: 仅左 (无 spine → MISSING)
        marketing("m-miss", "I-MISS", "USD", 500, "ISSUE", "PAID", BIZ);

        // EXTRA: 仅右
        accounting("a-extra", "I-EXTRA", "USD", 700, "ISSUE", "PAID", BIZ);

        // DUPLICATE: 左侧同 (entry_type, amount) 出现两次
        marketing("m-dup-1", "I-DUP", "USD", 300, "ISSUE", "PAID", BIZ);
        marketing("m-dup-2", "I-DUP", "USD", 300, "ISSUE", "PAID", BIZ);
        accounting("a-dup", "I-DUP", "USD", 600, "ISSUE", "PAID", BIZ);

        // GROUP_SUM_MISMATCH: 左侧红蓝字 (ISSUE 400 + REFUND -100 = 300) 与右侧 500 不等
        marketing("m-gsm-1", "I-GSM", "USD", 400, "ISSUE", "PAID", BIZ);
        marketing("m-gsm-2", "I-GSM", "USD", -100, "REFUND", "PAID", BIZ);
        accounting("a-gsm", "I-GSM", "USD", 500, "ISSUE", "PAID", BIZ);

        // STATUS_MISMATCH: 1:1 金额相等, 状态不同
        marketing("m-stat", "I-STAT", "USD", 200, "ISSUE", "PAID", BIZ);
        accounting("a-stat", "I-STAT", "USD", 200, "ISSUE", "PENDING", BIZ);

        // CURRENCY_MISMATCH: 两侧币种不同 (跨 USD/EUR 两桶)
        marketing("m-ccy", "I-CCY", "USD", 200, "ISSUE", "PAID", BIZ);
        accounting("a-ccy", "I-CCY", "EUR", 200, "ISSUE", "PAID", BIZ);

        JobExecution exec = launch(runId, 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 差异分类: 7 类各一条 (I-CLEAN 无差异)
        assertThat(discrepancyTypes(runId)).containsExactlyInAnyOrder(
                "AMOUNT_MISMATCH", "MISSING", "EXTRA", "DUPLICATE",
                "GROUP_SUM_MISMATCH", "STATUS_MISMATCH", "CURRENCY_MISMATCH");
        assertThat(count("discrepancy", runId)).isEqualTo(7);

        // 双向守恒: USD/EUR 两个 (segment,currency) 桶均闭合 (left/right residual = 0)
        assertThat(count("recon_report", runId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=1", Long.class, runId))
                .isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT left_residual_minor + right_residual_minor AS r FROM recon_report WHERE run_id=?",
                Long.class, runId)).allMatch(r -> r == 0L);

        // 权威侧 (USD 桶) expected_total = Σ 各类左额 = 3800; matched (干净匹配左额) = 1000
        assertThat(jdbc.queryForObject(
                "SELECT expected_total_minor FROM recon_report WHERE run_id=? AND currency='USD'",
                Long.class, runId)).isEqualTo(3800L);
        assertThat(jdbc.queryForObject(
                "SELECT matched_amount_minor FROM recon_report WHERE run_id=? AND currency='USD'",
                Long.class, runId)).isEqualTo(1000L);

        // Run 终态 COMPLETED (守恒闭合)
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        // staging 已落库 (marketing 9 + accounting 7 = 16 条)
        assertThat(count("recon_record", runId)).isEqualTo(16L);
    }

    @Test
    void cleanDataYieldsZeroDiscrepanciesAndBalanced() throws Exception {
        String runId = "run-e2e-clean";
        marketing("m-1", "I-1", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-1", "I-1", "USD", 1000, "ISSUE", "PAID", BIZ);
        marketing("m-2", "I-2", "USD", 2500, "ISSUE", "PAID", BIZ);
        accounting("a-2", "I-2", "USD", 2500, "ISSUE", "PAID", BIZ);

        JobExecution exec = launch(runId, 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(count("discrepancy", runId)).isZero();
        assertThat(count("recon_report", runId)).isEqualTo(1); // 单 USD 桶
        assertThat(jdbc.queryForObject(
                "SELECT balanced FROM recon_report WHERE run_id=? AND currency='USD'", Integer.class, runId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT matched_amount_minor FROM recon_report WHERE run_id=? AND currency='USD'",
                Long.class, runId)).isEqualTo(3500L);
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void malformedSourceRowGoesToRejectAndDoesNotInterruptFlow() throws Exception {
        String runId = "run-e2e-reject";
        marketing("m-ok", "I-OK", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-ok", "I-OK", "USD", 1000, "ISSUE", "PAID", BIZ);
        // 畸形行: null 币种 → 源适配器入 reject, 不中断整流
        jdbc.update("INSERT INTO recon_src_marketing"
                        + "(id, issue_id, ccy, amount_minor, entry_type, biz_status, biz_time, posting_time) "
                        + "VALUES ('m-bad','I-BAD', NULL, 500, 'ISSUE', 'PAID', ?, ?)",
                Timestamp.from(BIZ), Timestamp.from(BIZ));

        JobExecution exec = launch(runId, 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 畸形行落 reject, 不进 staging; 干净数据正常对账闭合
        assertThat(count("recon_record", runId)).isEqualTo(2L);           // 仅 m-ok + a-ok
        assertThat(count("recon_record_reject", runId)).isEqualTo(1L);    // m-bad
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM recon_record_reject WHERE run_id=?", String.class, runId))
                .contains("currency");
        assertThat(count("discrepancy", runId)).isZero();
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    private String runStatus(String runId) {
        return jdbc.queryForObject("SELECT status FROM recon_run WHERE run_id=?", String.class, runId);
    }
}
