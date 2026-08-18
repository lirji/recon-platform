package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.spi.RecordCursor;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 BucketPartitioner 覆盖性 (设计 §11 M3): 所有 group 落到<b>唯一</b> partition、<b>全部 bucket 被处理</b>、
 * 无漏无重; 数据倾斜二级 sub-bucket 开关生效时热点 bucket 拆多子分区、非热点不拆。纯单元 (免 Spring)。
 */
class BucketPartitionerTest {

    private static final String RUN = "run-part";
    private static final String SEG = "SEG1_MKT_ACCT";
    private static final int BUCKETS = 8;

    @Test
    void one_partition_per_bucket_covers_all_no_dup_when_subbucket_off() {
        SkewDetector detector = detector(uniform(BUCKETS, 10)); // 无倾斜
        BucketPartitioner p = partitioner(detector, /*subEnabled*/ false, /*fanout*/ 8);

        Map<String, ExecutionContext> parts = p.partition(4);

        assertThat(parts).hasSize(BUCKETS);
        boolean[] seen = new boolean[BUCKETS];
        for (Map.Entry<String, ExecutionContext> e : parts.entrySet()) {
            int bucket = e.getValue().getInt(BucketPartitioner.CTX_BUCKET);
            int subIndex = e.getValue().getInt(BucketPartitioner.CTX_SUB_INDEX);
            assertThat(e.getKey()).isEqualTo("bucket" + bucket);
            assertThat(subIndex).isEqualTo(-1);                     // 未拆二级
            assertThat(e.getValue().getString(BucketPartitioner.CTX_RUN_ID)).isEqualTo(RUN);
            assertThat(e.getValue().getString(BucketPartitioner.CTX_SEGMENT_ID)).isEqualTo(SEG);
            assertThat(seen[bucket]).as("bucket %d not duplicated", bucket).isFalse();
            seen[bucket] = true;
        }
        for (int b = 0; b < BUCKETS; b++) {
            assertThat(seen[b]).as("bucket %d covered", b).isTrue();  // 全部 bucket 被处理, 无漏
        }
    }

    @Test
    void ignores_grid_size_hint_partition_count_follows_bucket_count() {
        BucketPartitioner p = partitioner(detector(uniform(BUCKETS, 10)), false, 8);
        // gridSize 建议 1 / 100 都不改变分片数 = bucketCount。
        assertThat(p.partition(1)).hasSize(BUCKETS);
        assertThat(p.partition(100)).hasSize(BUCKETS);
    }

    @Test
    void hot_bucket_split_into_sub_partitions_when_subbucket_on() {
        // bucket 3 = 500 行 (远超其它 10 行 → 热点); factor=5, min-rows=100。
        Map<Integer, Long> counts = uniform(BUCKETS, 10);
        counts.put(3, 500L);
        int fanout = 4;
        BucketPartitioner p = partitioner(detector(counts), /*subEnabled*/ true, fanout);

        Map<String, ExecutionContext> parts = p.partition(4);

        // 非热点 7 桶各 1 partition + 热点 bucket 3 拆 fanout 个 = 7 + 4 = 11。
        assertThat(parts).hasSize((BUCKETS - 1) + fanout);

        // 热点 bucket 3: fanout 个子分区, subIndex 覆盖 0..fanout-1, 名 bucket3#subS。
        boolean[] subSeen = new boolean[fanout];
        int hotParts = 0;
        for (Map.Entry<String, ExecutionContext> e : parts.entrySet()) {
            int bucket = e.getValue().getInt(BucketPartitioner.CTX_BUCKET);
            int subIndex = e.getValue().getInt(BucketPartitioner.CTX_SUB_INDEX);
            if (bucket == 3) {
                hotParts++;
                assertThat(subIndex).isBetween(0, fanout - 1);
                assertThat(e.getKey()).isEqualTo("bucket3#sub" + subIndex);
                assertThat(e.getValue().getInt(BucketPartitioner.CTX_SUB_FANOUT)).isEqualTo(fanout);
                subSeen[subIndex] = true;
            } else {
                assertThat(subIndex).isEqualTo(-1);                 // 非热点不拆
                assertThat(e.getKey()).isEqualTo("bucket" + bucket);
            }
        }
        assertThat(hotParts).isEqualTo(fanout);
        for (int s = 0; s < fanout; s++) {
            assertThat(subSeen[s]).as("sub-bucket %d covered", s).isTrue();
        }
    }

    @Test
    void hot_bucket_not_split_when_subbucket_off() {
        Map<Integer, Long> counts = uniform(BUCKETS, 10);
        counts.put(3, 500L);
        // 检测到热点但开关关 → 仍每桶 1 partition (仅日志告警)。
        BucketPartitioner p = partitioner(detector(counts), /*subEnabled*/ false, 4);
        assertThat(p.partition(4)).hasSize(BUCKETS);
    }

    // ---------- helpers ----------

    private BucketPartitioner partitioner(SkewDetector detector, boolean subEnabled, int fanout) {
        return new BucketPartitioner(detector, SEG, RUN, "MARKETING_3WAY", "2026-08-17",
                1_000L, 2_000L, BUCKETS, subEnabled, fanout);
    }

    private SkewDetector detector(Map<Integer, Long> counts) {
        return new SkewDetector(new CountsOnlyRepo(counts), 5.0, 100L);
    }

    private static Map<Integer, Long> uniform(int buckets, long rows) {
        Map<Integer, Long> m = new LinkedHashMap<>();
        for (int b = 0; b < buckets; b++) {
            m.put(b, rows);
        }
        return m;
    }

    /** 只实现 bucketRowCounts 的假 repo (SkewDetector 仅用它)。 */
    private static final class CountsOnlyRepo implements ReconRecordRepository {
        private final Map<Integer, Long> counts;

        CountsOnlyRepo(Map<Integer, Long> counts) {
            this.counts = counts;
        }

        @Override
        public Map<Integer, Long> bucketRowCounts(String runId, String segmentId) {
            return counts;
        }

        @Override
        public void batchInsert(Iterable<ReconRecord> records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RecordCursor cursor(String runId, String segmentId, Side side, int bucket) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RecordCursor cursorBySegmentSide(String runId, String segmentId, Side side) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteByRunBounded(String runId, int limit) {
            throw new UnsupportedOperationException();
        }
    }
}
