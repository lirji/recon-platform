package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 数据倾斜检测 (M3, 设计 §6 / A8): 用 {@link ReconRecordRepository#bucketRowCounts} 拿各 bucket 行数,
 * 算均值并标记<b>热点 bucket</b> (行数 &gt; {@code mean × factor} 且 &gt;= {@code minRows})。
 *
 * <p><b>只检测 + 报告</b>, 不改判差/守恒结果 (幂等纯读)。热点集交 {@link BucketPartitioner}:
 * 关关闭时仅落日志; 打开二级 sub-bucket 开关时热点 bucket 拆多个并行子分区兜底 (设计"不必全自动")。
 *
 * <p>经 recon-core 端口读计数 (不碰 JDBC, 守 ArchUnit 门禁)。
 */
public class SkewDetector {

    private final ReconRecordRepository records;
    private final double factor;
    private final long minRows;

    public SkewDetector(ReconRecordRepository records, double factor, long minRows) {
        this.records = records;
        this.factor = factor;
        this.minRows = minRows;
    }

    public SkewReport detect(String runId, String segmentId) {
        Map<Integer, Long> counts = records.bucketRowCounts(runId, segmentId);
        if (counts.isEmpty()) {
            return new SkewReport(counts, 0.0, 0L, Set.of(), factor, minRows);
        }
        long total = 0L;
        long max = 0L;
        for (long c : counts.values()) {
            total += c;
            max = Math.max(max, c);
        }
        double mean = (double) total / counts.size();
        double threshold = mean * factor;

        Set<Integer> hot = new LinkedHashSet<>();
        for (Map.Entry<Integer, Long> e : counts.entrySet()) {
            long c = e.getValue();
            if (c >= minRows && c > threshold) {
                hot.add(e.getKey());
            }
        }
        return new SkewReport(counts, mean, max, hot, factor, minRows);
    }
}
