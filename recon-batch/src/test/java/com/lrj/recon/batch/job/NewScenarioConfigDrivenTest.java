package com.lrj.recon.batch.job;

import com.lrj.recon.batch.service.ScenarioDefinitionStore;
import com.lrj.recon.scenario.dsl.MarketingThreeWayDefinition;
import com.lrj.recon.scenario.dsl.ScenarioDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 · 「不改代码接入新场景」端到端证明:场景码 {@code MKT_3WAY_V2} 在<b>任何 Java 代码里都没有硬编码</b>,
 * 只作为配置数据存入 {@code recon_scenario_def};经通用引擎 {@code genericReconJob} 跑通 + 经 {@code ReconLaunchService}
 * 按 code 路由到通用引擎跑通。证明配置驱动执行成立。
 */
class NewScenarioConfigDrivenTest extends AbstractThreeWayJobIT {

    private static final String NEW_CODE = "MKT_3WAY_V2";

    @Autowired
    Job genericReconJob;

    @Autowired
    ScenarioDefinitionStore store;

    @Autowired
    ReconLaunchService launchService;

    /** 复用内置三方形态,仅换一个 Java 里不存在的场景码 —— 纯配置。 */
    private void seedNewScenario() {
        store.save(new ScenarioDefinition(NEW_CODE, MarketingThreeWayDefinition.seed().segments()), true);
    }

    private void seedData() {
        // SEG1 clean (O1) + SEG1 AMOUNT_MISMATCH (O2: 1000 vs 900); SEG2 两笔皆 clean。
        marketing("m1", "O1", "I1", "USD", 1000, "ISSUE", "PAID");
        accounting("a1", "O1", "I1", "C1", "USD", 1000, "ISSUE", "PAID");
        channel("c1", "C1", "USD", 1000, "ISSUE", "PAID");
        marketing("m2", "O2", "I2", "USD", 1000, "ISSUE", "PAID");
        accounting("a2", "O2", "I2", "C2", "USD", 900, "ISSUE", "PAID");
        channel("c2", "C2", "USD", 900, "ISSUE", "PAID");
    }

    @Test
    void config_only_scenario_runs_through_generic_engine() throws Exception {
        seedNewScenario();
        seedData();
        String runId = "MKT_3WAY_V2:run-direct";

        JobExecution exec = jobLauncher.run(genericReconJob,
                new ReconJobContext(runId, NEW_CODE, PERIOD, 1, CUTOFF, WINDOW_FROM, WINDOW_TO, BUCKET_COUNT, 1L)
                        .toJobParameters());

        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(discrepancyTypes(runId, SEG1)).containsExactly("AMOUNT_MISMATCH");
        assertThat(discrepancyTypes(runId, SEG2)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=1",
                Long.class, runId)).isEqualTo(2);
    }

    @Test
    void launch_service_routes_config_scenario_to_generic_engine() throws Exception {
        seedNewScenario();
        seedData();

        ReconLaunchService.LaunchResult result = launchService.launch(new ReconLaunchService.LaunchCommand(
                NEW_CODE, PERIOD, null, BUCKET_COUNT, CUTOFF, WINDOW_FROM, WINDOW_TO));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(discrepancyTypes(result.runId(), SEG1)).containsExactly("AMOUNT_MISMATCH");
    }

    @Test
    void unknown_scenario_code_fails_fast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> launchService.launch(
                new ReconLaunchService.LaunchCommand("NOT_A_SCENARIO", PERIOD, null, BUCKET_COUNT, CUTOFF, WINDOW_FROM, WINDOW_TO)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
