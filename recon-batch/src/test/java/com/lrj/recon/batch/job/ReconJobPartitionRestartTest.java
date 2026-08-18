package com.lrj.recon.batch.job;

import com.lrj.recon.core.domain.service.Bucketing;
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
 * M3 验收: <b>partition 断点续跑</b> (设计 §11 M3): 某 partition 失败 → 同参重启<b>只续未完成 partition</b>
 * (已 COMPLETE 的 worker 被跳过, 仅失败的重跑)。数据确定性落桶 (Bucketing.bucketOf 反推 key), 目标桶注入
 * 一次性失败; 断言各 worker step 执行次数 + 最终守恒闭合、差异分类正确。
 */
@Import(ReconJobPartitionRestartTest.PartitionGateConfig.class)
class ReconJobPartitionRestartTest extends AbstractReconJobIT {

    @Autowired TogglePartitionGate gate;

    private static final String RUN = "run-part-restart";
    private static final int BUCKETS = 4;

    @Test
    void failedPartitionRestartsAndResumesOnlyThatBucket() throws Exception {
        // 确定性落桶: 目标桶 0 放 AMOUNT_MISMATCH (注入失败); 桶 1 干净匹配; 桶 2 MISSING。桶 3 空。
        String kAmt = keyForBucket(0);
        String kClean = keyForBucket(1);
        String kMiss = keyForBucket(2);
        marketing("m-amt", kAmt, "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-amt", kAmt, "USD", 900, "ISSUE", "PAID", BIZ);
        marketing("m-clean", kClean, "USD", 500, "ISSUE", "PAID", BIZ);
        accounting("a-clean", kClean, "USD", 500, "ISSUE", "PAID", BIZ);
        marketing("m-miss", kMiss, "USD", 700, "ISSUE", "PAID", BIZ);

        // ---- 首启: 桶 0 的 worker 注入一次性失败 ----
        gate.targetBucket = 0;
        gate.failNext.set(true);
        JobExecution failed = launch(RUN, 1, BUCKETS);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(runStatus(RUN)).isEqualTo("LOADING");        // 未到 reportStep, 状态仍 LOADING
        assertThat(count("recon_report", RUN)).isZero();

        // 桶 0 worker FAILED; 桶 1/2/3 worker COMPLETED (各执行 1 次)
        assertThat(workerExecCount(0)).isEqualTo(1L);
        assertThat(workerStatus(0)).contains("FAILED");
        assertThat(workerExecCount(1)).isEqualTo(1L);
        assertThat(workerExecCount(2)).isEqualTo(1L);
        assertThat(workerExecCount(3)).isEqualTo(1L);

        // ---- 重启 (同参): 只续桶 0 ----
        JobExecution restarted = launch(RUN, 1, BUCKETS);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(runStatus(RUN)).isEqualTo("COMPLETED");

        // 断点续跑证据: 桶 0 执行 2 次 (失败 + 重放), 桶 1/2/3 仍各 1 次 (未重跑)
        assertThat(workerExecCount(0)).isEqualTo(2L);
        assertThat(workerExecCount(1)).isEqualTo(1L);
        assertThat(workerExecCount(2)).isEqualTo(1L);
        assertThat(workerExecCount(3)).isEqualTo(1L);

        // 结果正确: 差异分类 + 守恒闭合
        assertThat(discrepancyTypes(RUN)).containsExactlyInAnyOrder("AMOUNT_MISMATCH", "MISSING");
        assertThat(count("recon_report", RUN)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT balanced FROM recon_report WHERE run_id=? AND currency='USD'", Integer.class, RUN))
                .isEqualTo(1);
    }

    private String keyForBucket(int bucket) {
        for (int i = 0; ; i++) {
            String k = "IK-" + i;
            if (Bucketing.bucketOf(k, BUCKETS) == bucket) {
                return k;
            }
        }
    }

    private String runStatus(String runId) {
        return jdbc.queryForObject("SELECT status FROM recon_run WHERE run_id=?", String.class, runId);
    }

    /** 某 bucket 的 worker step 执行记录数 (STEP_NAME = matchEvaluateWorkerStep:bucket<b>)。 */
    private Long workerExecCount(int bucket) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE STEP_NAME = ?",
                Long.class, "matchEvaluateWorkerStep:bucket" + bucket);
    }

    private String workerStatus(int bucket) {
        return String.join(",", jdbc.queryForList(
                "SELECT STATUS FROM BATCH_STEP_EXECUTION WHERE STEP_NAME = ?",
                String.class, "matchEvaluateWorkerStep:bucket" + bucket));
    }

    /** 可切换的一次性分区故障闸 (测试用): 目标 bucket 的 chunk 写入前若 failNext 为真则抛错一次。 */
    static class TogglePartitionGate implements PartitionFailureGate {
        volatile int targetBucket = -1;
        final AtomicBoolean failNext = new AtomicBoolean(false);

        @Override
        public void beforeBucketWrite(int bucket) {
            if (bucket == targetBucket && failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("injected partition failure for bucket " + bucket);
            }
        }
    }

    @TestConfiguration
    static class PartitionGateConfig {
        @Bean
        TogglePartitionGate togglePartitionGate() {
            return new TogglePartitionGate();
        }
    }
}
