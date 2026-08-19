package com.lrj.recon.batch.job;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4 · {@link ReconJobMetricsListener} 单测: FAILED 自增 {@code recon.job.failures} + 记 duration(status=FAILED);
 * COMPLETED 只记 duration、不动失败计数器。用真实 {@link SimpleMeterRegistry},无 Spring 上下文。
 */
class ReconJobMetricsListenerTest {

    private static JobExecution execution(String job, String scenario, BatchStatus status) {
        JobParameters params = new JobParametersBuilder()
                .addString("scenarioCode", scenario)
                .addString("runId", "run-x")
                .toJobParameters();
        JobExecution ex = new JobExecution(new JobInstance(1L, job), 11L, params);
        ex.setStatus(status);
        ex.setStartTime(LocalDateTime.now().minusSeconds(2));
        ex.setEndTime(LocalDateTime.now());
        return ex;
    }

    @Test
    void failedJobIncrementsFailureCounterAndRecordsDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReconJobMetricsListener listener = new ReconJobMetricsListener(registry);

        JobExecution ex = execution("marketingThreeWayJob", "MARKETING_3WAY", BatchStatus.FAILED);
        ex.setExitStatus(ExitStatus.FAILED);
        ex.addFailureException(new IllegalStateException("boom"));

        listener.afterJob(ex);

        assertThat(registry.counter("recon.job.failures",
                "job", "marketingThreeWayJob", "scenario", "MARKETING_3WAY").count()).isEqualTo(1.0);
        assertThat(registry.find("recon.job.duration")
                .tag("job", "marketingThreeWayJob").tag("status", "FAILED").timer()).isNotNull();
    }

    @Test
    void completedJobRecordsDurationButNoFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReconJobMetricsListener listener = new ReconJobMetricsListener(registry);

        listener.afterJob(execution("reconciliationJob", "MARKETING_3WAY", BatchStatus.COMPLETED));

        assertThat(registry.find("recon.job.failures").counter()).isNull();
        assertThat(registry.find("recon.job.duration")
                .tag("job", "reconciliationJob").tag("status", "COMPLETED").timer()).isNotNull();
    }
}
