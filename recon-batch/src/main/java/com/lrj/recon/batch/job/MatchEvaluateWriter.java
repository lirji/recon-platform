package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ConservationPartialRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyRepository;
import com.lrj.recon.core.domain.service.ConservationAccumulator;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * M3 分桶并行 matchEvaluate 的 worker writer (设计 §6/§7/§8): 一个 partition = 一个 bucket, 在 chunk 事务内
 * <b>一次做三件事</b>:
 * <ol>
 *   <li><b>流式累计守恒</b>: 把每个 {@link EvaluatedGroup} (匹配与否) 喂给<b>本 partition 独立的</b>
 *       {@link ConservationAccumulator} (常量内存, 无共享可变状态);</li>
 *   <li><b>幂等 upsert discrepancy</b>: 仅对有差组按 {@code uk_disc(run_id, fingerprint)} 写入 (DuplicateKey 吞);</li>
 *   <li><b>落局部守恒结果</b>: upsert 当前"累计到此"的 {@link com.lrj.recon.core.domain.model.ConservationPartial}
 *       快照 (幂等键 run+segment+bucket+currency), partition 完成即该 bucket 的完整局部结果。</li>
 * </ol>
 * 三者同一 chunk 事务提交; discrepancy 与 partial 同源同批, 天然一致。消除 M2 report Step 的二次全量重放扫描。
 *
 * <p><b>断点/重放正确性</b>: reader 无内部 checkpoint (重启从桶头重读), accumulator 是 @StepScope 实例 (重启即新建、
 * 从 0 重累计), discrepancy/partial 均<b>幂等 upsert</b> —— 分区重放整桶后覆盖同键, 结果与一次成功等价。
 * partial 每 chunk 落"累计快照" (cumulative), 最后一 chunk 即完整值; 汇总步只在<b>所有</b> partition 完成后才读。
 */
public class MatchEvaluateWriter implements ItemWriter<EvaluatedGroup> {

    private final DiscrepancyRepository discrepancies;
    private final ConservationPartialRepository partials;
    private final PartitionFailureGate failureGate;
    private final String runId;
    private final String segmentId;
    private final int bucket;
    private final int subIndex;
    private final int subFanout;

    /** 本 partition 私有累加器 (跨该 partition 各 chunk 持续累计; @StepScope 保证每 partition 一个实例)。 */
    private final ConservationAccumulator accumulator = new ConservationAccumulator();

    /** #1: 本 worker 实例是否已清过本 bucket 的陈旧形状局部结果 (只需一次; @StepScope 每 partition 重跑即新实例重清)。 */
    private boolean staleCleaned;

    public MatchEvaluateWriter(DiscrepancyRepository discrepancies, ConservationPartialRepository partials,
                               PartitionFailureGate failureGate, String runId, String segmentId,
                               int bucket, int subIndex, int subFanout) {
        this.discrepancies = discrepancies;
        this.partials = partials;
        this.failureGate = failureGate;
        this.runId = runId;
        this.segmentId = segmentId;
        this.bucket = bucket;
        this.subIndex = subIndex;
        this.subFanout = subFanout;
    }

    @Override
    public void write(Chunk<? extends EvaluatedGroup> chunk) {
        failureGate.beforeBucketWrite(bucket); // 测试断点续跑: 对目标 bucket 一次性失败 (生产 no-op); 抛于任何 DB 写之前

        // #1: 首个 chunk 先清掉本 bucket 的陈旧形状局部结果 (shape-flip 残留), 再写本次快照 (在同 chunk 事务内);
        // 只清自己 bucket、不动同形状兄弟 → 无有害竞争, 保 partition 断点续跑。
        if (!staleCleaned) {
            partials.deleteStaleBucketPartials(runId, segmentId, bucket, subIndex, subFanout);
            staleCleaned = true;
        }

        for (EvaluatedGroup eg : chunk.getItems()) {
            accumulator.accept(eg.toClassified());       // 守恒累计: 全部组 (含干净匹配)
            if (eg.hasDiscrepancy()) {
                discrepancies.upsertByFingerprint(eg.discrepancy()); // 仅有差组落台账 (幂等)
            }
        }
        // 落"累计到当前"的局部守恒快照 (幂等键 run+segment+bucket+sub_index+currency); 最后一 chunk 即该子分区完整局部结果。
        if (!accumulator.isEmpty()) {
            partials.savePartials(accumulator.toPartials(runId, segmentId, bucket, subIndex));
        }
    }
}
