package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ConservationPartialRepository;
import com.lrj.recon.core.application.port.out.ReconReportRepository;
import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.domain.model.ConservationPartial;
import com.lrj.recon.core.domain.model.ReconReport;
import com.lrj.recon.core.domain.model.ReconRun;
import com.lrj.recon.core.domain.model.ReconRunStatus;
import com.lrj.recon.core.domain.service.ConservationMerger;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.Instant;
import java.util.List;

/**
 * Step3 reportStep (tasklet, 设计 §6/§7/§8) —— <b>M3 汇总步</b>: 跨 partition 合并局部守恒结果 → 写 recon_report
 * → 置终态。取代 M2 的二次全量重放守恒。
 *
 * <p><b>单遍守恒的汇总端</b>: matchEvaluateStep 各 partition 已在<b>匹配判差单遍</b>里流式累计并落
 * {@link ConservationPartial} 局部结果; 本 Step 只 {@link ConservationPartialRepository#listByRun} 读回,
 * 交 {@link ConservationMerger} 按 (segment, currency) 跨 bucket 合并复算最终 {@link ReconReport} (双向 residual
 * 判 balanced) —— 无游标、无重放, 与 M2 单线程双遍结果<b>逐字段等价</b> (共用 ConservationAccumulator)。
 *
 * <p><b>终态</b>: 全部 (segment,currency) 桶双向守恒闭合 → COMPLETED; 任一不闭合 → REPORT_IMBALANCE (乐观锁 save)。
 * LOADING → MATCHING 的推进保留在守恒判定<b>之前</b> (与 M2 一致), 使断点续跑语义不变 (reportStep 失败时 Run 仍 LOADING)。
 * 整个 tasklet 单事务, 失败回滚 → 断点续跑可重放。
 */
public class ReportTasklet implements Tasklet {

    private final ReconRunRepository runs;
    private final ReconReportRepository reports;
    private final ConservationPartialRepository partials;
    private final ConservationMerger merger;
    private final ReconJobContext ctx;
    private final StepFailureGate failureGate;

    public ReportTasklet(ReconRunRepository runs, ReconReportRepository reports,
                         ConservationPartialRepository partials, ConservationMerger merger,
                         ReconJobContext ctx, StepFailureGate failureGate) {
        this.runs = runs;
        this.reports = reports;
        this.partials = partials;
        this.merger = merger;
        this.ctx = ctx;
        this.failureGate = failureGate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        failureGate.beforeReport(ctx.runId()); // 测试断点续跑: 可控注入一次性失败 (生产 no-op)

        ReconRun run = runs.find(ctx.runId()).orElseThrow(
                () -> new IllegalStateException("run not found in reportStep: " + ctx.runId()));
        // 断点续跑幂等: LOADING → MATCHING (若已 MATCHING 则跳过, 直接进入终态判定)。
        if (run.status() == ReconRunStatus.LOADING) {
            runs.save(run.toMatching(), run.revision());
        }

        // 跨 partition 合并局部守恒结果 → 最终报表 (单遍守恒的汇总端, 无二次全量重放)。
        List<ConservationPartial> parts = partials.listByRun(ctx.runId());
        List<ReconReport> reconReports = merger.merge(ctx.runId(), parts);
        reports.saveAll(reconReports);

        boolean balanced = reconReports.stream().allMatch(ReconReport::balanced);
        ReconRun current = runs.find(ctx.runId()).orElseThrow();
        ReconRun terminal = balanced ? current.complete() : current.markImbalance();
        runs.save(terminal.toBuilder().finishedAt(Instant.now()).build(), current.revision());

        return RepeatStatus.FINISHED;
    }
}
