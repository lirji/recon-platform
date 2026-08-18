package com.lrj.recon.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 JobRepository 断点续跑 (设计验收 §12): Job 在 reportStep 失败 → 同参重启<b>只续未完成 Step</b>
 * (prepareRunStep/loadStep/matchEvaluateStep 已 COMPLETE 被跳过, 仅 reportStep 重放)。
 *
 * <p>用 {@link StepFailureGate} 一次性注入 reportStep 失败 (生产 no-op)。同 {runId, attempt} 两次启动 =
 * 断点续跑 (同一 JobInstance)。
 */
@Import(ReconJobRestartTest.FailingGateConfig.class)
class ReconJobRestartTest extends AbstractReconJobIT {

    @Autowired ToggleGate toggleGate;

    private static final String RUN = "run-restart";

    @Test
    void failedReportStepRestartsAndResumesOnlyUnfinishedStep() throws Exception {
        marketing("m-clean", "I-CLEAN", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-clean", "I-CLEAN", "USD", 1000, "ISSUE", "PAID", BIZ);
        marketing("m-amt", "I-AMT", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-amt", "I-AMT", "USD", 900, "ISSUE", "PAID", BIZ);

        // ---- 首启: reportStep 注入一次性失败 ----
        toggleGate.failNext.set(true);
        JobExecution failed = launch(RUN, 1);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        // reportStep 在守恒/置终态前抛错 (单事务回滚) → 状态仍 LOADING, 无报表
        assertThat(runStatus(RUN)).isEqualTo("LOADING");
        assertThat(count("recon_report", RUN)).isZero();
        // staging + 机器差异 已在前序 Step 提交
        assertThat(count("recon_record", RUN)).isEqualTo(4L);
        assertThat(discrepancyTypes(RUN)).containsExactly("AMOUNT_MISMATCH");

        // ---- 重启 (同参): 只续 reportStep ----
        JobExecution restarted = launch(RUN, 1);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runStatus(RUN)).isEqualTo("COMPLETED");
        assertThat(count("recon_report", RUN)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT balanced FROM recon_report WHERE run_id=? AND currency='USD'", Integer.class, RUN))
                .isEqualTo(1);

        // 断点续跑证据: 前序 Step 各只执行 1 次, 唯 reportStep 执行 2 次 (失败 + 重放)
        assertThat(stepExecCount("prepareRunStep")).isEqualTo(1L);
        assertThat(stepExecCount("loadStep")).isEqualTo(1L);
        assertThat(stepExecCount("matchEvaluateStep")).isEqualTo(1L);
        assertThat(stepExecCount("reportStep")).isEqualTo(2L);

        // 幂等: 重放未把 staging/差异翻倍
        assertThat(count("recon_record", RUN)).isEqualTo(4L);
        assertThat(count("discrepancy", RUN)).isEqualTo(1L);
    }

    private String runStatus(String runId) {
        return jdbc.queryForObject("SELECT status FROM recon_run WHERE run_id=?", String.class, runId);
    }

    private Long stepExecCount(String stepName) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE STEP_NAME = ?", Long.class, stepName);
    }

    /** 可切换的一次性故障闸 (测试用): reportStep 起点若 failNext 为真则抛错一次。 */
    static class ToggleGate implements StepFailureGate {
        final AtomicBoolean failNext = new AtomicBoolean(false);

        @Override
        public void beforeReport(String runId) {
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("injected reportStep failure for restart test: " + runId);
            }
        }
    }

    @TestConfiguration
    static class FailingGateConfig {
        @Bean
        ToggleGate toggleGate() {
            return new ToggleGate();
        }
    }
}
