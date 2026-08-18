package com.lrj.recon.batch.job;

import com.lrj.recon.core.domain.model.RunKey;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.Instant;

/**
 * 一次 Job 执行的运行时参数快照 (从 {@link JobParameters} 解出), 供各 Step 组件 (@JobScope) 共享。
 *
 * <p><b>Batch 实例身份 vs 业务 Run 身份</b> (设计 §6/ADR-12):
 * <ul>
 *   <li><b>identifying</b> = {@code runId} + {@code attempt}: 决定 Spring Batch 的 JobInstance。
 *       同 {runId, attempt} 再次启动 = <b>断点续跑</b> (只续未完成 Step); 换 {@code attempt} = 新实例,
 *       对应<b>业务重跑</b> (同一 runId 重算, Step0 走 cleanBounded 分批清机器结果)。</li>
 *   <li>其余 (scenario/period/seq/window/cutoff/bucketCount) 为 non-identifying: 只是 Step0 建 Run 的载荷,
 *       不参与实例身份。</li>
 * </ul>
 * 业务 Run 唯一性仍由 DB {@code uk_run(scenario_code, accounting_period, sequence_no)} 兜底 (claim 冲突挡并发)。
 */
public record ReconJobContext(
        String runId,
        String scenarioCode,
        String accountingPeriod,
        int sequenceNo,
        Instant cutoffTime,
        Instant matchWindowFrom,
        Instant matchWindowTo,
        int bucketCount,
        long attempt) {

    public static final String P_RUN_ID = "runId";
    public static final String P_ATTEMPT = "attempt";
    public static final String P_SCENARIO = "scenarioCode";
    public static final String P_PERIOD = "accountingPeriod";
    public static final String P_SEQ = "sequenceNo";
    public static final String P_CUTOFF = "cutoffTimeEpochMs";
    public static final String P_WINDOW_FROM = "matchWindowFromEpochMs";
    public static final String P_WINDOW_TO = "matchWindowToEpochMs";
    public static final String P_BUCKET_COUNT = "bucketCount";

    public RunKey key() {
        return RunKey.of(scenarioCode, accountingPeriod, sequenceNo);
    }

    /** 从 SpEL 注入的原始参数值构造 (long 型时间为 epoch millis)。 */
    public static ReconJobContext of(String runId, String scenarioCode, String accountingPeriod, long sequenceNo,
                                     long cutoffEpochMs, long windowFromEpochMs, long windowToEpochMs,
                                     long bucketCount, long attempt) {
        return new ReconJobContext(runId, scenarioCode, accountingPeriod, (int) sequenceNo,
                Instant.ofEpochMilli(cutoffEpochMs), Instant.ofEpochMilli(windowFromEpochMs),
                Instant.ofEpochMilli(windowToEpochMs), (int) bucketCount, attempt);
    }

    /** 组装 {@link JobParameters}: runId + attempt 为 identifying, 其余为载荷 (non-identifying)。 */
    public JobParameters toJobParameters() {
        return new JobParametersBuilder()
                .addString(P_RUN_ID, runId, true)
                .addLong(P_ATTEMPT, attempt, true)
                .addString(P_SCENARIO, scenarioCode, false)
                .addString(P_PERIOD, accountingPeriod, false)
                .addLong(P_SEQ, (long) sequenceNo, false)
                .addLong(P_CUTOFF, cutoffTime.toEpochMilli(), false)
                .addLong(P_WINDOW_FROM, matchWindowFrom.toEpochMilli(), false)
                .addLong(P_WINDOW_TO, matchWindowTo.toEpochMilli(), false)
                .addLong(P_BUCKET_COUNT, (long) bucketCount, false)
                .toJobParameters();
    }
}
