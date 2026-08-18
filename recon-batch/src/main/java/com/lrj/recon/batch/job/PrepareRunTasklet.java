package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.domain.model.ReconRun;
import com.lrj.recon.core.domain.model.ReconRunStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.Instant;
import java.util.Optional;

/**
 * Step0 {@code prepareRunStep} (tasklet, 设计 §6): claim/重置 Run + 分批清理机器结果, 置 {@code LOADING}。
 *
 * <ul>
 *   <li><b>首跑</b> (Run 不存在): {@link ReconRunRepository#claim} INSERT 命中 {@code uk_run} → 并发 Conflict 挡重复;
 *       随后 {@code start()} 置 CREATED→LOADING (revision 0→1)。</li>
 *   <li><b>重跑</b> (Run 已存在, 同 runId 新 attempt): 先 {@link ReconRerunService#cleanBounded} 分批清 staging +
 *       机器差异 (独立事务, 不碰人工表), 再重置到 LOADING。终态→LOADING 是组合根显式的<b>重跑重置</b>,
 *       刻意绕过 {@link ReconRun} 守恒态机 (态机只管正向生命周期), 保留 Run 身份/窗口/桶数。</li>
 * </ul>
 * 断点续跑时本 Step 若已 COMPLETE 会被 Spring Batch 跳过 (不重复 claim)。
 */
public class PrepareRunTasklet implements Tasklet {

    private final ReconRunRepository runs;
    private final ReconRerunService rerunService;
    private final ReconJobContext ctx;

    public PrepareRunTasklet(ReconRunRepository runs, ReconRerunService rerunService, ReconJobContext ctx) {
        this.runs = runs;
        this.rerunService = rerunService;
        this.ctx = ctx;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Instant now = Instant.now();
        Optional<ReconRun> existing = runs.find(ctx.runId());
        if (existing.isEmpty()) {
            ReconRun created = ReconRun.builder()
                    .runId(ctx.runId())
                    .key(ctx.key())
                    .cutoffTime(ctx.cutoffTime())
                    .matchWindowFrom(ctx.matchWindowFrom())
                    .matchWindowTo(ctx.matchWindowTo())
                    .bucketCount(ctx.bucketCount())
                    .status(ReconRunStatus.CREATED)
                    .revision(0)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            runs.claim(created);                 // INSERT; uk_run/PK 重复 → ConflictException
            runs.save(created.start(), 0);       // CREATED → LOADING, revision 0 → 1
        } else {
            rerunService.cleanBounded(ctx.runId()); // 分批清机器结果 (独立事务, 人工表零触碰)
            ReconRun r = existing.get();
            ReconRun reset = r.toBuilder()
                    .status(ReconRunStatus.LOADING)
                    .updatedAt(now)
                    .finishedAt(null)
                    .startedAt(r.startedAt() == null ? now : r.startedAt())
                    .build();
            runs.save(reset, r.revision());      // 乐观锁重置到 LOADING
        }
        return RepeatStatus.FINISHED;
    }
}
