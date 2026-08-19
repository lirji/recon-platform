package com.lrj.recon.batch.job;

import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

/**
 * B4 Phase 3b · 通用执行引擎用:在 step 启动时把该 step 对应的<b>段序号 {@code segmentIndex}</b> 写入执行上下文,
 * 供该 step 的共享 @StepScope 组件按序号从<b>本 run 的场景</b>(据 {@code jobParameters['scenarioCode']} 经
 * {@code ConfigScenarioService} 装配)解析 SegmentDef —— 从而同一个 {@code genericReconJob} 可跑任意同形态场景。
 *
 * <p>非分区 load step 与分区 manager step 用它(beforeStep 早于 @StepScope 组件首次 open);worker step 的
 * {@code segmentId}/{@code scenarioCode} 由 partitioner 写各分片上下文(既有机制),worker 直接按 segmentId 解析。
 */
public class SegmentStampListener implements StepExecutionListener {

    private final int segmentIndex;

    public SegmentStampListener(int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        stepExecution.getExecutionContext().putInt("segmentIndex", segmentIndex);
    }
}
