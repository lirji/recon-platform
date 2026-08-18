package com.lrj.recon.batch.job;

import com.lrj.recon.batch.service.ManualClearingService;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 · A1 重跑收敛 (设计 §14 A1, ADR-7) 端到端: 重跑绝不删 disposition/reversal, 且
 * <ul>
 *   <li><b>A1①</b> 人工已 RESOLVED 的差异重算后<b>仍出现</b> → re-link 保持 RESOLVED (不重开, version 不变);</li>
 *   <li><b>A1②</b> 处置过但重算后<b>消失</b> → 标 STALE 自动关闭 + 审计; 已生成的冲正建议幸存;</li>
 *   <li><b>A1③</b> 差异 type 变更 (MISSING→AMOUNT_MISMATCH) 致 fingerprint 变 → 旧处置 STALE、新差异 OPEN (无处置)。</li>
 * </ul>
 * 用单段 {@code reconciliationJob} (含 M5 convergenceStep) + {@link ManualClearingService} 真实核销。
 */
class DispositionConvergenceA1Test extends AbstractReconJobIT {

    @Autowired ManualClearingService manualClearing;

    @Test
    void a1_relinkKeepsResolvedWhenDiscrepancyPersists() throws Exception {
        String run = "run-a1-relink";
        marketing("m-amt", "I-AMT", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-amt", "I-AMT", "USD", 900, "ISSUE", "PAID", BIZ);

        assertThat(launch(run, 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        String did = discrepancyId(run, "AMOUNT_MISMATCH");
        String fp = fingerprint(run, "AMOUNT_MISMATCH");
        manualClearing.resolve(did, "ops", "known diff", null);

        // 重跑同数据 → 差异仍在 (同 fingerprint) → 收敛 re-link
        assertThat(launch(run, 2).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(dispositionStatus(fp)).isEqualTo("RESOLVED");            // 保持, 未重开
        assertThat(dispositionVersion(fp)).isEqualTo(0);                    // re-link 不 bump version
        assertThat(staleAuditCount(fp)).isZero();                           // 未标 STALE
    }

    @Test
    void a1_staleAutoClosesWhenDiscrepancyVanishesReversalSurvives() throws Exception {
        String run = "run-a1-stale";
        marketing("m-amt", "I-AMT", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-amt", "I-AMT", "USD", 900, "ISSUE", "PAID", BIZ);

        assertThat(launch(run, 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        String did = discrepancyId(run, "AMOUNT_MISMATCH");
        String fp = fingerprint(run, "AMOUNT_MISMATCH");
        manualClearing.resolve(did, "ops", null, null);
        // 首跑处理链已为该 AMOUNT_MISMATCH 生成冲正建议
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reversal_suggestion WHERE fingerprint=?",
                Long.class, fp)).isEqualTo(1L);

        // 修正账务金额使其重算后干净 → 差异消失
        jdbc.update("UPDATE recon_src_accounting SET amount_minor = 1000 WHERE id = 'a-amt'");
        assertThat(launch(run, 2).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("discrepancy", run)).isZero();

        assertThat(dispositionStatus(fp)).isEqualTo("STALE");               // 自动关闭
        assertThat(staleAuditCount(fp)).isEqualTo(1L);                       // 留审计
        // 冲正建议 / 处置行本身绝不被重跑删除 (ADR-7)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reversal_suggestion WHERE fingerprint=?",
                Long.class, fp)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy_disposition WHERE fingerprint=?",
                Long.class, fp)).isEqualTo(1L);
    }

    @Test
    void a1_typeChangeStalesOldOpensNew() throws Exception {
        String run = "run-a1-typechange";
        // 首跑: 仅营销 → MISSING
        marketing("m-x", "I-X", "USD", 1000, "ISSUE", "PAID", BIZ);
        assertThat(launch(run, 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        String missingId = discrepancyId(run, "MISSING");
        String missingFp = fingerprint(run, "MISSING");
        manualClearing.resolve(missingId, "ops", null, null);

        // 重跑: 补账务 900 → 变 AMOUNT_MISMATCH (新 fingerprint)
        accounting("a-x", "I-X", "USD", 900, "ISSUE", "PAID", BIZ);
        assertThat(launch(run, 2).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 旧 MISSING 处置 → STALE; 新 AMOUNT_MISMATCH 差异存在且无处置 (天然 OPEN)
        assertThat(dispositionStatus(missingFp)).isEqualTo("STALE");
        assertThat(staleAuditCount(missingFp)).isEqualTo(1L);
        String amountId = discrepancyId(run, "AMOUNT_MISMATCH");
        String amountFp = fingerprint(run, "AMOUNT_MISMATCH");
        assertThat(amountId).isNotEqualTo(missingId);                        // run-local 主键不同
        assertThat(amountFp).isNotEqualTo(missingFp);                        // type 变 → fingerprint 变
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM discrepancy_disposition WHERE fingerprint=?",
                Long.class, amountFp)).isZero();                             // 新差异 OPEN
        assertThat(discrepancyTypes(run)).containsExactly("AMOUNT_MISMATCH"); // 旧 MISSING 机器结果已清
    }

    // ---------- 助手 ----------

    private String discrepancyId(String run, String type) {
        return jdbc.queryForObject(
                "SELECT discrepancy_id FROM discrepancy WHERE run_id=? AND type=?", String.class, run, type);
    }

    private String fingerprint(String run, String type) {
        return jdbc.queryForObject(
                "SELECT fingerprint FROM discrepancy WHERE run_id=? AND type=?", String.class, run, type);
    }

    private String dispositionStatus(String fingerprint) {
        return jdbc.queryForObject(
                "SELECT status FROM discrepancy_disposition WHERE fingerprint=?", String.class, fingerprint);
    }

    private int dispositionVersion(String fingerprint) {
        return jdbc.queryForObject(
                "SELECT version FROM discrepancy_disposition WHERE fingerprint=?", Integer.class, fingerprint);
    }

    private long staleAuditCount(String fingerprint) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy_action WHERE fingerprint=? AND action_type='STALE_CLOSE'",
                Long.class, fingerprint);
    }
}
