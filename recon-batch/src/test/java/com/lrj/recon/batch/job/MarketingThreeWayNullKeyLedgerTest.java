package com.lrj.recon.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 修复 A 回归 (SegmentGroupCursor null 相位 fingerprint 碰撞 → 台账 undercount)。
 *
 * <p>SEG1 refine 段里, 同一 {@code group_key} (发放单号) 下的多条 <b>null match_key</b> (发放ID 列空) 记录被逐条
 * 路由为单条 {@code MatchGroup}。修复前 fingerprint 的 match_key 段折叠为 {@code '∅'} → 同 group_key 多条 null-key
 * 记录<b>共用同一 fingerprint</b> → {@code upsertByFingerprint} last-wins → 台账只留最后一条金额, 而
 * {@code ConservationAccumulator} 累计全额 → <b>台账 undercount, 但 residual≡0 骗过守恒门禁</b>。
 *
 * <p>修复后: null match_key 时以该单边组存在侧的 rawRef (table:pk, 跨重跑稳定) 作 fingerprint 鉴别量 → 每条 null-key
 * 记录得<b>唯一</b> fingerprint、各自成一行, <b>台账金额之和 == 守恒 bridge_broken/extra 额</b> (不再 undercount)。
 * 非 null-key 路径 fingerprint 不变 (保 A1 re-link)。
 */
class MarketingThreeWayNullKeyLedgerTest extends AbstractThreeWayJobIT {

    /** 干净三方链 (让两段都有 balanced 报表)。 */
    private void cleanChain() {
        marketing("m-1", "O1", "I1", "USD", 1000, "ISSUE", "PAID");
        accounting("a-1", "O1", "I1", "C1", "USD", 1000, "ISSUE", "PAID");
        channel("ch-1", "C1", "USD", 1000, "ISSUE", "PAID");
    }

    @Test
    void sameGroupNullKeyBridgeBrokenGetDistinctLedgerRowsSummingToConservedAmount() throws Exception {
        String runId = "run-3way-nullkey-bridge";
        cleanChain();

        // 同一发放单 O-NL 下两条 null 发放ID 营销记录 (400 / 300): 账务 spine 无对应 → SEG1 LEFT_ONLY → BRIDGE_BROKEN(SEG1)。
        // 修复前两条 fingerprint 相同 → 台账仅留 1 条 (300); 修复后 2 条独立行, 金额和 = 700。
        marketing("m-nl-1", "O-NL", null, "USD", 400, "ISSUE", "PAID");
        marketing("m-nl-2", "O-NL", null, "USD", 300, "ISSUE", "PAID");

        JobExecution exec = launch(runId, 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 2 条独立 BRIDGE_BROKEN(SEG1) 台账行 (修复前为 1 条: fingerprint 碰撞 last-wins)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy WHERE run_id=? AND segment_id=? AND type='BRIDGE_BROKEN'"
                        + " AND group_key='O-NL'", Long.class, runId, SEG1)).isEqualTo(2L);
        // 两行 fingerprint / discrepancy_id 互异 (记录级鉴别量 rawRef 生效)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT fingerprint) FROM discrepancy WHERE run_id=? AND segment_id=?"
                        + " AND type='BRIDGE_BROKEN' AND group_key='O-NL'", Long.class, runId, SEG1)).isEqualTo(2L);

        // 台账金额之和 = 700, 且 == 守恒 bridge_broken 额 (undercount 已消除)
        long ledgerSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(expected_amount_minor),0) FROM discrepancy WHERE run_id=? AND segment_id=?"
                        + " AND type='BRIDGE_BROKEN' AND group_key='O-NL'", Long.class, runId, SEG1);
        assertThat(ledgerSum).isEqualTo(700L);
        assertThat(reportLong(runId, SEG1, "bridge_broken_minor")).isEqualTo(ledgerSum);
        assertThat(reportLong(runId, SEG1, "bridge_broken_minor")).isEqualTo(700L);

        // 两段守恒仍闭合 balanced
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=1", Long.class, runId)).isEqualTo(2L);
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void sameGroupNullKeyExtraGetDistinctLedgerRowsSummingToConservedAmount() throws Exception {
        String runId = "run-3way-nullkey-extra";
        cleanChain();

        // 同一发放单 O-NR 下两条 null 发放ID 账务记录 (250 / 150): SEG1 营销侧无对应 → RIGHT_ONLY, 左/营销非 spine → EXTRA(SEG1)。
        // 各带渠道流水号并有渠道结算 → SEG2 干净。修复前两条 EXTRA fingerprint 相同 → 台账仅留 1 条; 修复后 2 条, 和 = 400。
        accounting("a-nr-1", "O-NR", null, "C-NR1", "USD", 250, "ISSUE", "PAID");
        accounting("a-nr-2", "O-NR", null, "C-NR2", "USD", 150, "ISSUE", "PAID");
        channel("ch-nr1", "C-NR1", "USD", 250, "ISSUE", "PAID");
        channel("ch-nr2", "C-NR2", "USD", 150, "ISSUE", "PAID");

        JobExecution exec = launch(runId, 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 2 条独立 EXTRA(SEG1) 台账行 (修复前为 1 条)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM discrepancy WHERE run_id=? AND segment_id=? AND type='EXTRA'"
                        + " AND group_key='O-NR'", Long.class, runId, SEG1)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT fingerprint) FROM discrepancy WHERE run_id=? AND segment_id=?"
                        + " AND type='EXTRA' AND group_key='O-NR'", Long.class, runId, SEG1)).isEqualTo(2L);

        // 台账金额之和 = 400 (右额 actual), == 守恒 extra 额
        long ledgerSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(actual_amount_minor),0) FROM discrepancy WHERE run_id=? AND segment_id=?"
                        + " AND type='EXTRA' AND group_key='O-NR'", Long.class, runId, SEG1);
        assertThat(ledgerSum).isEqualTo(400L);
        assertThat(reportLong(runId, SEG1, "extra_minor")).isEqualTo(ledgerSum);
        assertThat(reportLong(runId, SEG1, "extra_minor")).isEqualTo(400L);

        // SEG2 干净, 两段 balanced
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
