package com.lrj.recon.batch.job;

import com.lrj.recon.batch.config.SubBucketPolicy;
import com.lrj.recon.core.domain.service.Bucketing;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5 / KI-1 守卫回归: sub-bucket <b>开</b>下, restart 前把 <b>fanout 数值</b>(8→4)改掉 —— 子分片数变、worker 级
 * stale-partial 清理不覆盖, 会静默双算/漏算而 residual≡0 骗过守恒。{@link SkewConfigGuardListener} 应在 restart 的
 * {@code beforeJob} <b>fail-fast</b>(而非静默错算)。
 *
 * <p>对照 {@link ReconJobShapeFlipRestartTest}:那里是<b>单次</b>整桶↔sub 翻转(fanout 不变),属已缓解、应<b>放行</b>;
 * 本测试是 fanout 变,属 KI-1 残留、应<b>拒绝</b>。二者共同界定守卫边界。
 */
@TestPropertySource(properties = {
        "recon.skew.factor=2.0",
        "recon.skew.min-rows=5"
})
@Import(ReconJobSkewFanoutRestartGuardTest.GuardConfig.class)
class ReconJobSkewFanoutRestartGuardTest extends AbstractReconJobIT {

    @Autowired MutableSubBucketPolicy policy;
    @Autowired FailGate gate;

    private static final String RUN = "run-skew-fanout-guard";
    private static final int BUCKETS = 8;

    @Test
    void fanoutChangeBeforeRestartFailsFast() throws Exception {
        for (int i = 0; i < 6; i++) {
            String k = keyForBucket(0, "H" + i);
            long amt = 100 + i;
            marketing("m-" + i, k, "USD", amt, "ISSUE", "PAID", BIZ);
            accounting("a-" + i, k, "USD", amt, "ISSUE", "PAID", BIZ);
        }

        // 首启: sub-bucket ON, fanout=8; 注入 bucket 0 一次性 partition 失败 → Run FAILED
        policy.enabled = true;
        policy.fanout = 8;
        gate.targetBucket = 0;
        gate.failNext.set(true);
        JobExecution failed = launch(RUN, 1, BUCKETS);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);

        // restart 前把 fanout 改成 4 (enabled 仍 true) → 守卫 fail-fast
        policy.fanout = 4;
        JobExecution restarted = launch(RUN, 1, BUCKETS);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(restarted.getAllFailureExceptions())
                .anyMatch(t -> t.getMessage() != null && t.getMessage().contains("KI-1"));
    }

    private String keyForBucket(int bucket, String prefix) {
        for (int i = 0; ; i++) {
            String k = prefix + "-" + i;
            if (Bucketing.bucketOf(k, BUCKETS) == bucket) {
                return k;
            }
        }
    }

    static class MutableSubBucketPolicy implements SubBucketPolicy {
        volatile boolean enabled;
        volatile int fanout = 8;

        @Override public boolean enabled() { return enabled; }
        @Override public int fanout() { return fanout; }
    }

    static class FailGate implements PartitionFailureGate {
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
    static class GuardConfig {
        @Bean
        @Primary
        MutableSubBucketPolicy mutableSubBucketPolicy() {
            return new MutableSubBucketPolicy();
        }

        @Bean
        FailGate failGate() {
            return new FailGate();
        }
    }
}
