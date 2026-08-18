package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.Bucketing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 数据倾斜<b>检测</b> (设计 §6 / A8): 单 bucket 行数远超均值即热点。构造热点 bucket, 断言 {@link SkewDetector}
 * 标记它、其余不标; 阈值 = 均值 × factor 且 &gt;= minRows。守恒闭合由并行端到端/sub-bucket 测试覆盖。
 */
class SkewDetectorTest extends AbstractReconJobIT {

    private static final String SEG = "SEG1_MKT_ACCT";
    private static final String RUN = "run-skew-detect";
    private static final int BUCKETS = 8;

    @Autowired ReconRecordRepository records;

    @Test
    void flagsHotBucketAboveMeanTimesFactor() {
        // bucket 0: 15 组 (30 行, 热点); bucket 1/2 各 1 组 (2 行)。
        List<ReconRecord> seed = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String k = keyForBucket(0, "H" + i);
            seed.add(rec(Side.LEFT, k));
            seed.add(rec(Side.RIGHT, k));
        }
        for (int b : new int[]{1, 2}) {
            String k = keyForBucket(b, "C" + b);
            seed.add(rec(Side.LEFT, k));
            seed.add(rec(Side.RIGHT, k));
        }
        records.batchInsert(seed);

        SkewReport report = new SkewDetector(records, 2.0, 5L).detect(RUN, SEG);

        assertThat(report.hasSkew()).isTrue();
        assertThat(report.isHot(0)).isTrue();          // 30 > mean(≈11.3) × 2 且 >= 5
        assertThat(report.isHot(1)).isFalse();
        assertThat(report.isHot(2)).isFalse();
        assertThat(report.hotBuckets()).containsExactly(0);
        assertThat(report.bucketCounts().get(0)).isEqualTo(30L);
        assertThat(report.maxRows()).isEqualTo(30L);
    }

    @Test
    void noSkewWhenBucketsBalanced() {
        List<ReconRecord> seed = new ArrayList<>();
        for (int b = 0; b < 4; b++) {
            String k = keyForBucket(b, "B" + b);
            seed.add(rec(Side.LEFT, k));
            seed.add(rec(Side.RIGHT, k));
        }
        records.batchInsert(seed);

        SkewReport report = new SkewDetector(records, 2.0, 5L).detect(RUN, SEG);
        assertThat(report.hasSkew()).isFalse();
        assertThat(report.hotBuckets()).isEmpty();
    }

    private String keyForBucket(int bucket, String prefix) {
        for (int i = 0; ; i++) {
            String k = prefix + "-" + i;
            if (Bucketing.bucketOf(k, BUCKETS) == bucket) {
                return k;
            }
        }
    }

    private ReconRecord rec(Side side, String key) {
        int bucket = Bucketing.bucketOf(key, BUCKETS);
        return ReconRecord.builder()
                .recordId(RUN + ":" + side + ":" + key)
                .runId(RUN).segmentId(SEG).side(side)
                .sourceRole(side == Side.LEFT ? SourceRole.MARKETING : SourceRole.ACCOUNTING)
                .matchKey(MatchKey.of("k", key, bucket))
                .groupKey(GroupKey.of("k", key))
                .bucket(bucket)
                .money(Money.of("USD", 100))
                .entryType(EntryType.ISSUE)
                .bizTime(BIZ)
                .rawRef("t:" + key)
                .build();
    }
}
