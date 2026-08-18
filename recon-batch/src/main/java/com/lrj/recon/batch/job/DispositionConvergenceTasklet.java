package com.lrj.recon.batch.job;

import com.lrj.recon.batch.service.DispositionConvergenceService;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * {@code convergenceStep} (tasklet, 设计 §14 A1): 报表后、告警中继前, 对本 Run 重算结果做人工处置收敛
 * (A1①re-link 保持 / A1②③标 STALE 自动关闭)。委托 {@link DispositionConvergenceService} (纯端口, 不碰批框架)。
 *
 * <p>需在 matchEvaluate 产出本次机器差异之后运行 (收敛按 fingerprint 比对本次差异集); 放在 reportStep 之后不影响
 * 报表与终态。首跑 (无历史处置) 为廉价 no-op。
 */
public class DispositionConvergenceTasklet implements Tasklet {

    private final DispositionConvergenceService convergenceService;
    private final ReconJobContext ctx;

    public DispositionConvergenceTasklet(DispositionConvergenceService convergenceService, ReconJobContext ctx) {
        this.convergenceService = convergenceService;
        this.ctx = ctx;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        convergenceService.converge(ctx.runId(), ctx.scenarioCode(), ctx.accountingPeriod());
        return RepeatStatus.FINISHED;
    }
}
