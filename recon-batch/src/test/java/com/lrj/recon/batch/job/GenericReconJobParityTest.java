package com.lrj.recon.batch.job;

import com.lrj.recon.batch.service.ScenarioDefinitionStore;
import com.lrj.recon.scenario.dsl.MarketingThreeWayDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 Phase 3b · 通用执行引擎 parity: {@code genericReconJob}(据 AssembledScenario 动态编排 N 段)在与
 * {@link MarketingThreeWayEndToEndTest} <b>相同数据集</b>上产出与既有 {@code marketingThreeWayJob} <b>一致</b>的
 * 分类 / bridge 归因 / 双向守恒。锁定「通用引擎 ≡ 硬编码两段 job」,证明动态编排正确。
 */
class GenericReconJobParityTest extends AbstractThreeWayJobIT {

    @Autowired
    Job genericReconJob;

    @Autowired
    ScenarioDefinitionStore scenarioStore;

    private JobExecution launchGeneric(String runId) throws Exception {
        return jobLauncher.run(genericReconJob,
                new ReconJobContext(runId, SCENARIO, PERIOD, 1, CUTOFF, WINDOW_FROM, WINDOW_TO, BUCKET_COUNT, 1L)
                        .toJobParameters());
    }

    private long reportLong(String runId, String segmentId, String column) {
        Long v = jdbc.queryForObject("SELECT " + column + " FROM recon_report WHERE run_id=? AND segment_id=?",
                Long.class, runId, segmentId);
        return v == null ? 0L : v;
    }

    @Test
    void generic_engine_matches_hardcoded_marketing_job() throws Exception {
        String runId = "run-generic-parity";
        // 通用引擎从配置存储按 scenarioCode 解析场景;显式 seed MARKETING_3WAY 定义(不依赖启动 seed 的存活)。
        scenarioStore.save(MarketingThreeWayDefinition.seed(), true);

        // 与 MarketingThreeWayEndToEndTest 同一数据集
        marketing("m-I1a", "O1", "I1a", "USD", 1000, "ISSUE", "PAID");
        marketing("m-I1b", "O1", "I1b", "USD", 2000, "ISSUE", "PAID");
        accounting("a-I1a", "O1", "I1a", "C1a", "USD", 1000, "ISSUE", "PAID");
        accounting("a-I1b", "O1", "I1b", "C1b", "USD", 2000, "ISSUE", "PAID");
        channel("ch-C1a", "C1a", "USD", 1000, "ISSUE", "PAID");
        channel("ch-C1b", "C1b", "USD", 2000, "ISSUE", "PAID");

        marketing("m-I2", "O2", "I2", "USD", 1000, "ISSUE", "PAID");
        accounting("a-I2", "O2", "I2", "C2", "USD", 900, "ISSUE", "PAID");
        channel("ch-C2", "C2", "USD", 900, "ISSUE", "PAID");

        marketing("m-I3", "O3", "I3", "USD", 500, "ISSUE", "PAID"); // SEG1 BRIDGE_BROKEN

        marketing("m-I5a", "O5", "I5", "USD", 400, "ISSUE", "PAID");
        marketing("m-I5b", "O5", "I5", "USD", -100, "REFUND", "PAID");
        accounting("a-I5", "O5", "I5", "C5", "USD", 500, "ISSUE", "PAID");
        channel("ch-C5", "C5", "USD", 500, "ISSUE", "PAID");

        marketing("m-I6", "O6", "I6", "USD", 800, "ISSUE", "PAID");
        accounting("a-I6", "O6", "I6", "C6", "USD", 800, "ISSUE", "PAID");
        channel("ch-C6", "C6", "USD", 700, "ISSUE", "PAID"); // SEG2 AMOUNT_MISMATCH

        marketing("m-I8", "O8", "I8", "USD", 300, "ISSUE", "PAID");
        accounting("a-I8", "O8", "I8", "C8", "USD", 300, "ISSUE", "PAID"); // SEG2 MISSING (C8 无渠道)

        channel("ch-C9", "C9", "USD", 700, "ISSUE", "PAID"); // SEG2 BRIDGE_BROKEN

        JobExecution exec = launchGeneric(runId);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(discrepancyTypes(runId, SEG1))
                .containsExactlyInAnyOrder("AMOUNT_MISMATCH", "BRIDGE_BROKEN", "GROUP_SUM_MISMATCH");
        assertThat(discrepancyTypes(runId, SEG2))
                .containsExactlyInAnyOrder("AMOUNT_MISMATCH", "MISSING", "BRIDGE_BROKEN");

        assertThat(jdbc.queryForObject(
                "SELECT bridge_break_stage FROM discrepancy WHERE run_id=? AND segment_id=? AND type='BRIDGE_BROKEN'",
                String.class, runId, SEG1)).isEqualTo("SEG1");
        assertThat(jdbc.queryForObject(
                "SELECT bridge_break_stage FROM discrepancy WHERE run_id=? AND segment_id=? AND type='BRIDGE_BROKEN'",
                String.class, runId, SEG2)).isEqualTo("SEG2");

        // 各段双向守恒 balanced, residual=0
        assertThat(count("recon_report", runId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=1", Long.class, runId)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT left_residual_minor + right_residual_minor AS r FROM recon_report WHERE run_id=?",
                Long.class, runId)).allMatch(r -> r == 0L);

        assertThat(reportLong(runId, SEG1, "expected_total_minor")).isEqualTo(5900L);
        assertThat(reportLong(runId, SEG1, "matched_amount_minor")).isEqualTo(4100L);
        assertThat(reportLong(runId, SEG1, "bridge_broken_minor")).isEqualTo(500L);
        assertThat(reportLong(runId, SEG2, "expected_total_minor")).isEqualTo(5500L);
        assertThat(reportLong(runId, SEG2, "matched_amount_minor")).isEqualTo(4400L);
        assertThat(reportLong(runId, SEG2, "missing_minor")).isEqualTo(300L);
    }
}
