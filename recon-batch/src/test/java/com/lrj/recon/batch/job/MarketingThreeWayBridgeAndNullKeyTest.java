package com.lrj.recon.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 桥接断链归因 + null match_key 路由 (KI-2 真触发)。
 *
 * <ul>
 *   <li><b>BRIDGE_BROKEN 分 stage</b>: 账务 spine 缺发放ID → SEG1 断 (stage=SEG1); 账务缺渠道流水号 → SEG2 断
 *       (stage=SEG2)。压制 MISSING, 一组一条主类型。</li>
 *   <li><b>null match_key 路由</b>: SEG1 是 refine 段 (match=发放ID != group=发放单号), 记录可有非空 group_key
 *       但 null match_key (发放ID 列空)。per-bucket 游标可移植 NULL 排序 + SegmentGroupCursor null 相位把这些
 *       记录逐条路由为单边组、<b>绝不喂给拒 null 的 SortMergeJoiner</b> —— 若喂了, worker step 会抛异常、Job FAILED。
 *       Job COMPLETED 即证明路由正确; 再校验产出的差异类型无误。</li>
 * </ul>
 */
class MarketingThreeWayBridgeAndNullKeyTest extends AbstractThreeWayJobIT {

    @Test
    void bridgeBreakStageIsAttributedToTheBrokenSegment() throws Exception {
        String runId = "run-3way-bridge";

        // 干净链 (让两段都有报表)
        marketing("m-1", "O1", "I1", "USD", 1000, "ISSUE", "PAID");
        accounting("a-1", "O1", "I1", "C1", "USD", 1000, "ISSUE", "PAID");
        channel("ch-1", "C1", "USD", 1000, "ISSUE", "PAID");

        // SEG1 断: 账务 spine 缺发放ID I2 (仅营销)
        marketing("m-2", "O2", "I2", "USD", 500, "ISSUE", "PAID");

        // SEG2 断: 渠道有流水号 C9 但账务 spine 缺
        channel("ch-9", "C9", "USD", 700, "ISSUE", "PAID");

        JobExecution exec = launch(runId, 1);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(discrepancyTypes(runId, SEG1)).containsExactly("BRIDGE_BROKEN");
        assertThat(discrepancyTypes(runId, SEG2)).containsExactly("BRIDGE_BROKEN");
        assertThat(jdbc.queryForObject(
                "SELECT bridge_break_stage FROM discrepancy WHERE run_id=? AND segment_id=?",
                String.class, runId, SEG1)).isEqualTo("SEG1");
        assertThat(jdbc.queryForObject(
                "SELECT bridge_break_stage FROM discrepancy WHERE run_id=? AND segment_id=?",
                String.class, runId, SEG2)).isEqualTo("SEG2");
        // 一组一条主类型: 断链侧不叠 MISSING
        assertThat(count("discrepancy", runId)).isEqualTo(2);
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }

    @Test
    void nullMatchKeyIsRoutedThroughNullPhaseNotIntoTheJoiner() throws Exception {
        String runId = "run-3way-nullkey";

        // 干净链
        marketing("m-1", "O1", "I1", "USD", 1000, "ISSUE", "PAID");
        accounting("a-1", "O1", "I1", "C1", "USD", 1000, "ISSUE", "PAID");
        channel("ch-1", "C1", "USD", 1000, "ISSUE", "PAID");

        // null 键(左): 营销发放ID 为空 (但发放单号 O-NL 非空可分桶) → SEG1 null 相位 LEFT_ONLY → BRIDGE_BROKEN(SEG1)
        marketing("m-nl", "O-NL", null, "USD", 400, "ISSUE", "PAID");

        // null 键(右): 账务发放ID 为空 (发放单号 O-NR 非空; 有渠道流水号 C-NR 使 SEG2 干净) → SEG1 null 相位 RIGHT_ONLY → EXTRA
        accounting("a-nr", "O-NR", null, "C-NR", "USD", 600, "ISSUE", "PAID");
        channel("ch-nr", "C-NR", "USD", 600, "ISSUE", "PAID");

        JobExecution exec = launch(runId, 1);
        // Job COMPLETED 即证明 null 键被路由出 join (未喂 SortMergeJoiner, 否则 worker step 抛异常 → FAILED)
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // staging 确有 null match_key 记录 (发放ID 列空, 发放单号非空), 且落在 hash(group_key) 桶
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_record WHERE run_id=? AND segment_id=? AND match_key IS NULL",
                Long.class, runId, SEG1)).isEqualTo(2L);

        // null 相位产正确差异: 左空 → BRIDGE_BROKEN(SEG1, 右/spine 缺), 右空 → EXTRA (左/营销非 spine)
        assertThat(discrepancyTypes(runId, SEG1)).containsExactlyInAnyOrder("BRIDGE_BROKEN", "EXTRA");
        assertThat(jdbc.queryForObject(
                "SELECT bridge_break_stage FROM discrepancy WHERE run_id=? AND segment_id=? AND type='BRIDGE_BROKEN'",
                String.class, runId, SEG1)).isEqualTo("SEG1");

        // SEG2 干净 (C1 + C-NR 都配上)
        assertThat(count("discrepancy", runId)).isEqualTo(2L); // 都在 SEG1
        assertThat(discrepancyTypes(runId, SEG2)).isEmpty();

        // 两段守恒闭合
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=1", Long.class, runId)).isEqualTo(2L);
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
    }
}
