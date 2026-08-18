package com.lrj.recon.batch.job;

import com.lrj.recon.core.domain.service.Bucketing;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 数据倾斜<b>二级 sub-bucket 兜底</b> (设计 §6, 可选开关): 打开 {@code recon.skew.sub-bucket.enabled} 后,
 * 热点 bucket 被拆成 {@code fanout} 个并行子分区 (按 match_key 二级散列, 保 join 对齐 + 守恒不重漏), 非热点不拆;
 * <b>守恒仍闭合、差异分类不变</b>。
 */
@TestPropertySource(properties = {
        "recon.skew.sub-bucket.enabled=true",
        "recon.skew.sub-bucket.fanout=4",
        "recon.skew.factor=2.0",
        "recon.skew.min-rows=5"
})
class ReconJobSubBucketTest extends AbstractReconJobIT {

    private static final String RUN = "run-subbucket";
    private static final int BUCKETS = 8;
    private static final int FANOUT = 4;

    @Test
    void hotBucketSplitsIntoSubPartitionsAndConservationBalances() throws Exception {
        // 热点 bucket 0: 12 组干净匹配 + 1 组 AMOUNT_MISMATCH (共 13 组, 26 行); bucket 1/2 各 1 组干净。
        long expectedMatchedClean = 0L;
        for (int i = 0; i < 12; i++) {
            String k = keyForBucket(0, "H" + i);
            long amt = 100 + i;
            marketing("m-" + i, k, "USD", amt, "ISSUE", "PAID", BIZ);
            accounting("a-" + i, k, "USD", amt, "ISSUE", "PAID", BIZ);
            expectedMatchedClean += amt;
        }
        String kAmt = keyForBucket(0, "AMT");
        marketing("m-amt", kAmt, "USD", 1000, "ISSUE", "PAID", BIZ);   // AMOUNT_MISMATCH (bucket 0)
        accounting("a-amt", kAmt, "USD", 900, "ISSUE", "PAID", BIZ);
        for (int b : new int[]{1, 2}) {
            String k = keyForBucket(b, "C" + b);
            long amt = 50 + b;
            marketing("m-c" + b, k, "USD", amt, "ISSUE", "PAID", BIZ);
            accounting("a-c" + b, k, "USD", amt, "ISSUE", "PAID", BIZ);
            expectedMatchedClean += amt;
        }

        JobExecution exec = launch(RUN, 1, BUCKETS);
        assertThat(exec.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 热点 bucket 0 拆成 FANOUT 个子分区 worker (bucket0#sub0..3), 无整桶 bucket0 分区
        assertThat(subPartitionCount(0)).isEqualTo((long) FANOUT);
        assertThat(wholeBucketExecCount(0)).isZero();
        // 非热点 bucket 1/2 为整桶单分区 (无 #sub)
        assertThat(wholeBucketExecCount(1)).isEqualTo(1L);
        assertThat(subPartitionCount(1)).isZero();
        assertThat(wholeBucketExecCount(2)).isEqualTo(1L);

        // 分类不变 + 守恒闭合 (sub-bucket 对守恒无感)
        assertThat(discrepancyTypes(RUN)).containsExactly("AMOUNT_MISMATCH");
        assertThat(count("recon_report", RUN)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT balanced FROM recon_report WHERE run_id=? AND currency='USD'", Integer.class, RUN))
                .isEqualTo(1);
        // 干净匹配额 = 所有干净组左额之和 (AMOUNT_MISMATCH 组不计入 matched)
        assertThat(jdbc.queryForObject(
                "SELECT matched_amount_minor FROM recon_report WHERE run_id=? AND currency='USD'",
                Long.class, RUN)).isEqualTo(expectedMatchedClean);
        // 局部结果确实按 sub_index 分开落 (bucket 0 有多个 sub_index 行, 未互相覆盖)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT sub_index) FROM recon_report_partial WHERE run_id=? AND bucket=0",
                Integer.class, RUN)).isGreaterThan(1);
    }

    private long subPartitionCount(int bucket) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE STEP_NAME LIKE ?",
                Long.class, "matchEvaluateWorkerStep:bucket" + bucket + "#sub%");
    }

    private long wholeBucketExecCount(int bucket) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE STEP_NAME = ?",
                Long.class, "matchEvaluateWorkerStep:bucket" + bucket);
    }

    private String keyForBucket(int bucket, String prefix) {
        for (int i = 0; ; i++) {
            String k = prefix + "-" + i;
            if (Bucketing.bucketOf(k, BUCKETS) == bucket) {
                return k;
            }
        }
    }
}
