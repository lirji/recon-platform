package com.lrj.recon.batch.job;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A4 可观测性 · 批作业失败告警(计量 + 结构化日志侧)。挂在 {@code reconciliationJob} / {@code marketingThreeWayJob}
 * 上,{@code afterJob} 里:
 * <ul>
 *   <li>无条件记 {@code recon.job.duration} 计时器(维度 {@code job} + {@code status}),补 Spring Batch 自动
 *       {@code spring.batch.job} 计量,便于 Grafana 看端到端耗时分布;</li>
 *   <li>{@link BatchStatus#FAILED} 时:自增 {@code recon.job.failures} 计数器(维度 {@code job} + {@code scenario}),
 *       并打一条<b>结构化 ERROR</b> 日志(键控字段 job/scenario/runId/exitCode/reason)——供 Prometheus 告警规则
 *       (如 {@code increase(recon_job_failures_total[15m]) > 0})或日志告警键控;真正外发通道是 A2 的
 *       {@code @Primary AlertDispatcher},本类只负责让"失败"这一事实在计量与日志中<b>可见且可告警</b>。</li>
 * </ul>
 *
 * <p>不改判差/落库语义,不吞异常(仅在 Job 结束后旁路观测);MeterRegistry 由 actuator + micrometer 自动装配提供。
 */
@Component
public class ReconJobMetricsListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ReconJobMetricsListener.class);

    private final MeterRegistry meters;

    public ReconJobMetricsListener(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String job = jobExecution.getJobInstance().getJobName();
        String scenario = jobExecution.getJobParameters().getString("scenarioCode", "unknown");
        String runId = jobExecution.getJobParameters().getString("runId", "unknown");
        BatchStatus status = jobExecution.getStatus();

        recordDuration(jobExecution, job, status);

        if (status == BatchStatus.FAILED) {
            meters.counter("recon.job.failures", "job", job, "scenario", scenario).increment();
            String reason = jobExecution.getAllFailureExceptions().stream()
                    .findFirst()
                    .map(Throwable::toString)
                    .orElseGet(() -> jobExecution.getExitStatus().getExitDescription());
            log.error("recon job FAILED job={} scenario={} runId={} exitCode={} reason={}",
                    job, scenario, runId, jobExecution.getExitStatus().getExitCode(), reason);
        }
    }

    /** Spring Batch 5 的 start/end time 为 {@code LocalDateTime}; 缺任一(未真正开跑)则跳过计时。 */
    private void recordDuration(JobExecution ex, String job, BatchStatus status) {
        if (ex.getStartTime() == null || ex.getEndTime() == null) {
            return;
        }
        Timer.builder("recon.job.duration")
                .description("对账 Job 端到端耗时")
                .tag("job", job)
                .tag("status", status.name())
                .register(meters)
                .record(Duration.between(ex.getStartTime(), ex.getEndTime()));
    }
}
