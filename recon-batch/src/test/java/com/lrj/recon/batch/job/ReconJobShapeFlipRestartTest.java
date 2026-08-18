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
 * #1 shape-flip 静默错报 (设计 §6/§8, ADR-7): 断点续跑跳过 prepareRunStep → cleanBounded 不清 partial; 若两次运行
 * 之间 skew 配置翻转 (sub-bucket 开→关), 某桶从二级 sub-bucket 变整桶, 上次拆分残留的 sub_index 局部结果不被覆盖,
 * 汇总步<b>双算金额</b>而 residual 仍构造性 ≡0 骗过 balanced 门禁 (静默翻倍)。
 *
 * <p>本测试: sub-bucket <b>开</b> + 热点 bucket 0 → 制造 partition 失败 (matchEvaluateStep FAILED, 已完成的 sub-worker
 * 留下 sub_index 局部结果); sub-bucket <b>关</b> 后同参 restart → bucket 0 变整桶重跑, worker 先
 * {@code deleteStaleBucketPartials} 清掉陈旧 sub_index 行再写整桶快照 → 断言最终报表金额<b>不翻倍</b>、balanced 正确、
 * 无残留 sub_index 行。
 */
@TestPropertySource(properties = {
        "recon.skew.factor=2.0",
        "recon.skew.min-rows=5"
})
@Import(ReconJobShapeFlipRestartTest.ShapeFlipConfig.class)
class ReconJobShapeFlipRestartTest extends AbstractReconJobIT {

    @Autowired MutableSubBucketPolicy policy;
    @Autowired ShapeFlipGate gate;

    private static final String RUN = "run-shape-flip";
    private static final int BUCKETS = 8;
    private static final int FANOUT = 4;

    @Test
    void subBucketToWholeRestartDoesNotDoubleCountPartials() throws Exception {
        // 热点 bucket 0: 12 组干净匹配; bucket 1/2 各 1 组干净。全 USD。
        long expectedMatched = 0L;
        for (int i = 0; i < 12; i++) {
            String k = keyForBucket(0, "H" + i);
            long amt = 100 + i;
            marketing("m-" + i, k, "USD", amt, "ISSUE", "PAID", BIZ);
            accounting("a-" + i, k, "USD", amt, "ISSUE", "PAID", BIZ);
            expectedMatched += amt;
        }
        for (int b : new int[]{1, 2}) {
            String k = keyForBucket(b, "C" + b);
            long amt = 50 + b;
            marketing("m-c" + b, k, "USD", amt, "ISSUE", "PAID", BIZ);
            accounting("a-c" + b, k, "USD", amt, "ISSUE", "PAID", BIZ);
            expectedMatched += amt;
        }

        // ---- 首启: sub-bucket ON (fanout 4), bucket 0 拆 4 子分区; 注入一次性 partition 失败 ----
        policy.enabled = true;
        policy.fanout = FANOUT;
        gate.targetBucket = 0;
        gate.failNext.set(true);
        JobExecution failed = launch(RUN, 1, BUCKETS);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        // 已完成的 bucket 0 sub-worker 留下 sub_index>=0 的陈旧局部结果 (至少若干行)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report_partial WHERE run_id=? AND bucket=0 AND sub_index>=0",
                Long.class, RUN)).isGreaterThan(0L);

        // ---- restart: sub-bucket OFF → bucket 0 变整桶 (shape flip) ----
        policy.enabled = false;
        JobExecution restarted = launch(RUN, 1, BUCKETS);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 金额不翻倍: 报表 matched == 全部干净组左额之和 (若陈旧 sub 行未清会 > 此值)
        assertThat(count("recon_report", RUN)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT matched_amount_minor FROM recon_report WHERE run_id=? AND currency='USD'",
                Long.class, RUN)).isEqualTo(expectedMatched);
        assertThat(jdbc.queryForObject(
                "SELECT balanced FROM recon_report WHERE run_id=? AND currency='USD'", Integer.class, RUN))
                .isEqualTo(1);
        // bucket 0 陈旧 sub_index 行已被整桶 worker 清掉, 只剩整桶 sub_index=-1 一行
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report_partial WHERE run_id=? AND bucket=0 AND sub_index>=0",
                Long.class, RUN)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report_partial WHERE run_id=? AND bucket=0 AND sub_index=-1",
                Long.class, RUN)).isEqualTo(1L);
    }

    private String keyForBucket(int bucket, String prefix) {
        for (int i = 0; ; i++) {
            String k = prefix + "-" + i;
            if (Bucketing.bucketOf(k, BUCKETS) == bucket) {
                return k;
            }
        }
    }

    /** 可翻转的 sub-bucket 策略 (在两次 launch 之间切换形状)。 */
    static class MutableSubBucketPolicy implements SubBucketPolicy {
        volatile boolean enabled;
        volatile int fanout = FANOUT;

        @Override public boolean enabled() { return enabled; }
        @Override public int fanout() { return fanout; }
    }

    /** 一次性 partition 故障闸 (对目标 bucket 首个 chunk 写入抛一次)。 */
    static class ShapeFlipGate implements PartitionFailureGate {
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
    static class ShapeFlipConfig {
        @Bean
        @Primary
        MutableSubBucketPolicy mutableSubBucketPolicy() {
            return new MutableSubBucketPolicy();
        }

        @Bean
        ShapeFlipGate shapeFlipGate() {
            return new ShapeFlipGate();
        }
    }
}
