package com.lrj.recon.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #7 多 chunk 中途 restart (cumulative partial 覆盖正确性): 让单个 bucket 的组数 &gt; chunk size, partition 跨
 * 多个 chunk; 在<b>第 2 个 chunk</b> 注入一次性失败 → 断点续跑 → partition 从桶头整体重放, 局部守恒快照被
 * <b>正确覆盖</b>(而非翻倍/减半), 最终报表金额正确。
 *
 * <p>调小 {@code recon.match.chunk-size=3} + bucketCount=1 (全落 bucket 0) 造 7 组 → 3 个 chunk (3/3/1)。
 */
@TestPropertySource(properties = "recon.match.chunk-size=3")
@Import(ReconJobMultiChunkRestartTest.SecondChunkGateConfig.class)
class ReconJobMultiChunkRestartTest extends AbstractReconJobIT {

    @Autowired SecondChunkGate gate;

    private static final String RUN = "run-multichunk-restart";
    private static final int GROUPS = 7;

    @Test
    void secondChunkFailureRestartsAndOverwritesCumulativePartialCorrectly() throws Exception {
        long expectedMatched = 0L;
        for (int i = 0; i < GROUPS; i++) {
            long amt = 100 + i;
            String key = "IK-" + i;
            marketing("m-" + i, key, "USD", amt, "ISSUE", "PAID", BIZ);
            accounting("a-" + i, key, "USD", amt, "ISSUE", "PAID", BIZ);
            expectedMatched += amt;
        }

        // ---- 首启: bucket 0 的第 2 个 chunk 注入一次性失败 ----
        gate.arm(0, 2);
        JobExecution failed = launch(RUN, 1, 1);   // bucketCount=1 → 单 partition bucket 0
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(count("recon_report", RUN)).isZero();

        // ---- 重启 (同参): partition 从桶头整体重放, 3 chunk 全跑完 ----
        JobExecution restarted = launch(RUN, 1, 1);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // bucket 0 worker 执行 2 次 (失败 + 重放), 证明确实跨 chunk 中途失败后重启
        assertThat(workerExecCount(0)).isEqualTo(2L);

        // cumulative partial 被正确覆盖 (非翻倍/减半): 报表 matched = 全部 7 组左额之和, 且守恒闭合
        assertThat(count("recon_report", RUN)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT matched_amount_minor FROM recon_report WHERE run_id=? AND currency='USD'",
                Long.class, RUN)).isEqualTo(expectedMatched);
        assertThat(jdbc.queryForObject(
                "SELECT balanced FROM recon_report WHERE run_id=? AND currency='USD'", Integer.class, RUN))
                .isEqualTo(1);
        // 局部结果单行 (未因重放留下重复/翻倍行)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report_partial WHERE run_id=? AND bucket=0", Long.class, RUN))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT matched_left_minor FROM recon_report_partial WHERE run_id=? AND bucket=0",
                Long.class, RUN)).isEqualTo(expectedMatched);
        assertThat(count("discrepancy", RUN)).isZero(); // 全干净匹配
    }

    private Long workerExecCount(int bucket) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE STEP_NAME = ?",
                Long.class, "matchEvaluateWorkerStep:bucket" + bucket);
    }

    /** 对目标 bucket 的第 N 次 chunk 写入抛一次性失败 (跨两次 launch 只失败一次)。 */
    static class SecondChunkGate implements PartitionFailureGate {
        private volatile int targetBucket = -1;
        private volatile int failOnCall = -1;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean fired;

        void arm(int bucket, int nthChunk) {
            this.targetBucket = bucket;
            this.failOnCall = nthChunk;
            this.calls.set(0);
            this.fired = false;
        }

        @Override
        public void beforeBucketWrite(int bucket) {
            if (bucket != targetBucket) {
                return;
            }
            int n = calls.incrementAndGet();
            if (!fired && n == failOnCall) {
                fired = true;
                throw new IllegalStateException("injected mid-partition (chunk " + n + ") failure for bucket " + bucket);
            }
        }
    }

    @TestConfiguration
    static class SecondChunkGateConfig {
        @Bean
        SecondChunkGate secondChunkGate() {
            return new SecondChunkGate();
        }
    }
}
