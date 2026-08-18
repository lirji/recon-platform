package com.lrj.recon.batch.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M3 分桶并行的 {@link Partitioner} (设计 §6 Step2 / §11 M3): 造 {@code 0..N-1} 个 partition, <b>每个 partition
 * 绑定一个 bucket</b>。因 bucket = hash(group_key), 每个 group_key 恰落唯一 bucket, 故按 bucket 切分对 group_key
 * <b>无漏无重</b>、组不跨 partition。(M4 放宽 refine 后 match_key 可 != group_key; 「同一 match_key 簇不跨 partition」
 * 不再由 IDENTITY 保证, 而依赖数据满足 refine 函数性 —— 见 {@code StandardizeProcessor} 对 refine 校验的说明。)
 *
 * <p><b>数据倾斜兜底</b> (可选, 默认关): 若 {@code subBucketEnabled} 且某 bucket 被 {@link SkewDetector} 判为热点,
 * 则把它拆成 {@code subFanout} 个二级子分区 (按 match_key 二级散列, 见 {@code Bucketing.subIndexOf}), 分摊热点并行度;
 * 非热点 bucket 仍为单 partition。守恒对 bucket/sub-bucket 无感 (汇总按 segment,currency), 拆分不破闭合。
 *
 * <p><b>无 @JobScope 依赖</b>: 本 partitioner 在 manager step (主线程) 运行, 把 worker 所需的<b>全部运行时上下文</b>
 * (runId / scenario / period / 窗口 / bucket / subIndex / subFanout) 写进各 partition 的 {@link ExecutionContext};
 * worker step 的 @StepScope 组件只从 {@code stepExecutionContext} 取 —— 避免在并行 worker 线程上解析 @JobScope 失败。
 *
 * <p>partition 名 (map key) 确定性: {@code bucket<b>} 或 {@code bucket<b>#sub<s>}, 同数据重启复现同名, 保
 * Spring Batch 断点续跑只重跑未完成 partition。
 */
public class BucketPartitioner implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(BucketPartitioner.class);

    public static final String CTX_RUN_ID = "runId";
    public static final String CTX_SEGMENT_ID = "segmentId";
    public static final String CTX_SCENARIO = "scenarioCode";
    public static final String CTX_PERIOD = "accountingPeriod";
    public static final String CTX_WINDOW_FROM = "matchWindowFromEpochMs";
    public static final String CTX_WINDOW_TO = "matchWindowToEpochMs";
    public static final String CTX_BUCKET = "bucket";
    public static final String CTX_SUB_INDEX = "subIndex";     // -1 = 整桶 (未拆二级)
    public static final String CTX_SUB_FANOUT = "subFanout";

    private final SkewDetector skewDetector;
    private final String segmentId;
    private final String runId;
    private final String scenarioCode;
    private final String accountingPeriod;
    private final long windowFromEpochMs;
    private final long windowToEpochMs;
    private final int bucketCount;
    private final boolean subBucketEnabled;
    private final int subFanout;

    public BucketPartitioner(SkewDetector skewDetector, String segmentId, String runId, String scenarioCode,
                             String accountingPeriod, long windowFromEpochMs, long windowToEpochMs,
                             int bucketCount, boolean subBucketEnabled, int subFanout) {
        this.skewDetector = skewDetector;
        this.segmentId = segmentId;
        this.runId = runId;
        this.scenarioCode = scenarioCode;
        this.accountingPeriod = accountingPeriod;
        this.windowFromEpochMs = windowFromEpochMs;
        this.windowToEpochMs = windowToEpochMs;
        this.bucketCount = bucketCount;
        this.subBucketEnabled = subBucketEnabled;
        this.subFanout = subFanout;
    }

    /**
     * @param gridSize Spring Batch 传入的建议分片数 —— <b>本 partitioner 忽略它</b>, 分片数由 bucketCount (+ 热点拆分)
     *                 决定 (SimpleStepExecutionSplitter 按返回 map 的条目数建 worker, 不受 gridSize 限)。
     */
    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int effectiveBuckets = Math.max(1, bucketCount);
        SkewReport skew = skewDetector.detect(runId, segmentId);
        if (skew.hasSkew()) {
            // #4: sub-bucket 拆分是"用 IO 放大 (热桶被重复扫 fanout 遍) 换判差并行度"的取舍, 如实告警, 不美化为"已缓解"。
            String subBucket = subBucketEnabled
                    ? "SPLIT(fanout=" + subFanout + ", 代价=热桶重复扫描×" + subFanout + ")"
                    : "OFF(仅告警, 未拆; 如需并行判差可开 recon.skew.sub-bucket.enabled, 注意扫描放大)";
            log.warn("data skew detected run={} segment={} {} subBucket={}",
                    runId, segmentId, skew.summary(), subBucket);
        } else {
            log.info("bucket distribution run={} segment={} {}", runId, segmentId, skew.summary());
        }

        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        for (int bucket = 0; bucket < effectiveBuckets; bucket++) {
            if (subBucketEnabled && subFanout > 1 && skew.isHot(bucket)) {
                for (int sub = 0; sub < subFanout; sub++) {
                    partitions.put("bucket" + bucket + "#sub" + sub, context(bucket, sub));
                }
            } else {
                partitions.put("bucket" + bucket, context(bucket, -1));
            }
        }
        return partitions;
    }

    private ExecutionContext context(int bucket, int subIndex) {
        ExecutionContext ctx = new ExecutionContext();
        ctx.putString(CTX_RUN_ID, runId);
        ctx.putString(CTX_SEGMENT_ID, segmentId);
        ctx.putString(CTX_SCENARIO, scenarioCode);
        ctx.putString(CTX_PERIOD, accountingPeriod);
        ctx.putLong(CTX_WINDOW_FROM, windowFromEpochMs);
        ctx.putLong(CTX_WINDOW_TO, windowToEpochMs);
        ctx.putInt(CTX_BUCKET, bucket);
        ctx.putInt(CTX_SUB_INDEX, subIndex);
        ctx.putInt(CTX_SUB_FANOUT, subFanout);
        return ctx;
    }
}
