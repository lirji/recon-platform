package com.lrj.recon.batch.job;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 数据倾斜检测结果 (M3, 设计 §6): 某 (run, segment) 下各 bucket 行数分布 + 热点 bucket 判定。
 *
 * <p>不可变快照。{@link #hotBuckets()} = 行数 &gt; {@code mean × factor} 且 &gt;= {@code minRows} 的 bucket 集,
 * 供 {@link BucketPartitioner} 决定是否对其拆二级 sub-bucket, 并用于告警日志。
 */
public record SkewReport(
        Map<Integer, Long> bucketCounts,
        double mean,
        long maxRows,
        Set<Integer> hotBuckets,
        double factor,
        long minRows) {

    public boolean hasSkew() {
        return !hotBuckets.isEmpty();
    }

    public boolean isHot(int bucket) {
        return hotBuckets.contains(bucket);
    }

    /** 单行告警摘要 (供 partitioner 日志)。 */
    public String summary() {
        return "buckets=" + new TreeMap<>(bucketCounts).size()
                + ", mean=" + String.format("%.1f", mean)
                + ", max=" + maxRows
                + ", hot(>" + String.format("%.1f", mean * factor) + " & >=" + minRows + ")=" + new TreeMap<>(toHotCounts());
    }

    private Map<Integer, Long> toHotCounts() {
        Map<Integer, Long> m = new TreeMap<>();
        for (Integer b : hotBuckets) {
            m.put(b, bucketCounts.get(b));
        }
        return m;
    }
}
