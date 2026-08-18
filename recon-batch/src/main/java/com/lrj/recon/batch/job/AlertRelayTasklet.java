package com.lrj.recon.batch.job;

import com.lrj.recon.batch.alert.AlertRelayService;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Step4 {@code alertRelayStep} (tasklet, 设计 §6/§7 / ADR-10): Job 主体 (判差/报表) 提交后, 中继投递
 * {@code alert_outbox} 里本次入队的 PENDING 告警 (兼补投历史 FAILED)。
 *
 * <p><b>关键</b>: 外部告警投递彻底脱离可重试的 chunk 事务 —— 本 Step 在报表/收敛之后运行, {@link AlertRelayService}
 * 每条一短事务标 SENT/FAILED。投递失败<b>不使本 Step / 整个 Job 失败</b> (失败条目留 FAILED + attempt, 由
 * {@link ReconScheduler} 的 {@code @Scheduled} 补投), 故本 tasklet 恒返回 FINISHED, 不回滚已定终态的 Run。
 */
public class AlertRelayTasklet implements Tasklet {

    private final AlertRelayService relayService;

    public AlertRelayTasklet(AlertRelayService relayService) {
        this.relayService = relayService;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        relayService.relayOnce();
        return RepeatStatus.FINISHED;
    }
}
