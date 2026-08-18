package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.service.Bucketing;
import com.lrj.recon.core.spi.RecordCursor;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;

/**
 * M3 分桶并行 matchEvaluate 的 worker reader (设计 §6 Step2): 一个 partition = 一个 bucket, 用<b>单桶</b>两侧
 * 惰性游标 {@code cursor(run,seg,side,bucket)} 归并, 以"整组 = 一 item"发射 (同组不被 chunk 切断)。
 * 归并/聚合/null 兜底复用 {@link SegmentGroupCursor}。
 *
 * <p><b>二级 sub-bucket 过滤的取舍 (#4 诚实标注, 无"已缓解"美化)</b>: 当 {@code subIndex >= 0} 时, 本 worker
 * 仍<b>扫描整个热桶</b>的两侧游标, 但只发射 {@link Bucketing#subIndexOf} == subIndex 的组, 其余在内存跳过。
 * 因此热桶被拆成 {@code subFanout} 个子分区时, 该桶数据会被<b>重复扫描 {@code subFanout} 遍</b> (每个子分区各扫一遍)
 * —— 这是一次<b>用 IO 放大换并行度</b>的取舍: 当热桶瓶颈在<b>判差 CPU</b> 而非 IO 时划算 (fanout 路并行判差),
 * 反之 (IO 瓶颈) 会适得其反。无法把 subIndex 过滤下推到 DB: {@code subIndexOf} 依赖 Java {@code String.hashCode}
 * + avalanche 混合, 无可移植 SQL 表达式能与之逐位一致 (下推会破坏左右同键落同子分区的对齐)。故 sub-bucket
 * <b>默认关</b>, 仅作显式 opt-in 的倾斜兜底; 开启代价 (fanout× 扫描) 由 BucketPartitioner 的告警日志与
 * application.yml 注释如实标注。同键左右两侧同 subIndex → sort-merge 仍对齐; 每组恰属唯一 subIndex → 守恒不重不漏。
 * {@code subIndex < 0} 表示整桶 (未拆二级, 单遍扫描, 无放大)。
 *
 * <p>每 partition worker 是 @StepScope 独立实例 (fresh cursor), 无共享可变状态; 断点续跑 open() 从桶头重读
 * (无内部 checkpoint), 配合下游幂等 upsert 保证重放正确。
 */
public class BucketGroupReader implements ItemStreamReader<MatchGroup> {

    private final ReconRecordRepository records;
    private final String runId;
    private final String segmentId;
    private final int bucket;
    private final int subIndex;
    private final int subFanout;

    private SegmentGroupCursor cursor;

    public BucketGroupReader(ReconRecordRepository records, String runId, String segmentId,
                             int bucket, int subIndex, int subFanout) {
        this.records = records;
        this.runId = runId;
        this.segmentId = segmentId;
        this.bucket = bucket;
        this.subIndex = subIndex;
        this.subFanout = subFanout;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        // #3: 先开 LEFT, 再 try 开 RIGHT; 若 RIGHT 打开抛异常, 先关掉已开的 LEFT 流式游标 (连接) 再 rethrow,
        // 避免 LEFT 游标连接泄漏 (原两参同时求值, RIGHT 抛错则 LEFT 无人 close)。
        RecordCursor leftCursor = records.cursor(runId, segmentId, Side.LEFT, bucket);
        RecordCursor rightCursor;
        try {
            rightCursor = records.cursor(runId, segmentId, Side.RIGHT, bucket);
        } catch (RuntimeException e) {
            leftCursor.close();
            throw e;
        }
        this.cursor = new SegmentGroupCursor(leftCursor, rightCursor);
    }

    @Override
    public MatchGroup read() {
        if (cursor == null) {
            return null;
        }
        MatchGroup g;
        while ((g = cursor.next()) != null) {
            if (subIndex < 0 || Bucketing.subIndexOf(g, subFanout) == subIndex) {
                return g;
            }
            // 二级 sub-bucket: 不属本子分区的组跳过 (由其它子分区处理)。
        }
        return null;
    }

    @Override
    public void close() {
        if (cursor != null) {
            cursor.close();
            cursor = null;
        }
    }
}
