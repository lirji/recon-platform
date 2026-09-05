package com.lrj.recon.batch.job;

import com.lrj.recon.batch.config.GenericReconJobConfig;
import com.lrj.recon.batch.service.ConfigScenarioService;
import com.lrj.recon.batch.service.NotFoundException;
import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.application.port.out.ReconRunSeqRepository;
import com.lrj.recon.core.domain.model.ReconRun;
import com.lrj.recon.core.domain.model.RunKey;
import com.lrj.recon.scenario.BenefitCashThreeWayScenario;
import com.lrj.recon.scenario.dsl.AssembledScenario;
import com.lrj.recon.entitlement.scenario.EntitlementFulfillmentScenario;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Run 发起服务 (设计 §7/§11 M5 / ADR-12): scheduler 与 REST 走<b>同一路径</b>发起对账 Run —— 经
 * {@link ReconRunSeqRepository} 原子分配序号 (无 {@code MAX+1} 竞态), 组装 {@link ReconJobContext} 并 launch Job。
 *
 * <p><b>置于 {@code job} 包</b>: 合法持有 {@link JobLauncher}/{@link Job} (ArchUnit 允许 Spring Batch 出现在
 * job/config)。对外只返回纯 {@link LaunchResult} (无批类型泄漏), 供 REST 层零框架耦合调用。
 * 首发 attempt=1; 重跑 (rerun) 用新 attempt (时间戳) 形成新 JobInstance, Step0 {@code cleanBounded} 分批清机器结果、
 * 保留人工痕迹 (ADR-7)。
 */
@Service
public class ReconLaunchService {

    private static final int MAX_BUCKET_COUNT = 4096;
    private static final int MAX_SCENARIO_CODE_LENGTH = 32;

    private final JobLauncher jobLauncher;
    private final Map<String, Job> jobs;               // Spring 注入全部 Job bean (bean 名为键)
    private final ReconRunSeqRepository seqRepo;
    private final ReconRunRepository runs;
    private final ConfigScenarioService scenarios;     // B4: 配置驱动场景(不存在/停用/段数不符则 fail-fast)
    private final int defaultBucketCount;
    private final String defaultScenarioCode;
    private final String defaultJobName;
    private final String genericJobName;
    private final String entitlementJobName;

    public ReconLaunchService(JobLauncher jobLauncher,
                              Map<String, Job> jobs,
                              ReconRunSeqRepository seqRepo,
                              ReconRunRepository runs,
                              ConfigScenarioService scenarios,
                              @Value("${recon.launch.bucket-count:64}") int defaultBucketCount,
                              @Value("${recon.launch.scenario-code:MARKETING_3WAY}") String defaultScenarioCode,
                              @Value("${recon.launch.default-job:marketingThreeWayJob}") String defaultJobName,
                              @Value("${recon.launch.generic-job:genericReconJob}") String genericJobName,
                              @Value("${recon.launch.entitlement-job:entitlementFulfillmentJob}") String entitlementJobName) {
        this.jobLauncher = jobLauncher;
        this.jobs = jobs;
        this.seqRepo = seqRepo;
        this.runs = runs;
        this.scenarios = scenarios;
        this.defaultBucketCount = defaultBucketCount;
        this.defaultScenarioCode = requireText("configured scenarioCode", defaultScenarioCode);
        this.defaultJobName = requireText("configured default jobName", defaultJobName);
        this.genericJobName = requireText("configured generic jobName", genericJobName);
        this.entitlementJobName = requireText("configured entitlement jobName", entitlementJobName);
    }

    /** REST/scheduler 发起入口: 分配序号 + launch。 */
    public LaunchResult launch(LaunchCommand cmd) {
        if (cmd == null) {
            throw new IllegalArgumentException("launch command must not be null");
        }
        String scenario = requireText("scenarioCode", cmd.scenarioCode());
        String tenantId = cmd.tenantId() == null ? RunKey.LEGACY_TENANT : requireText("tenantId", cmd.tenantId());
        if ((BenefitCashThreeWayScenario.SCENARIO_CODE.equals(scenario)
                || EntitlementFulfillmentScenario.SCENARIO_CODE.equals(scenario))
                && RunKey.LEGACY_TENANT.equals(tenantId)) {
            throw new IllegalArgumentException("tenantId is required for benefit reconciliation");
        }
        if (scenario.length() > MAX_SCENARIO_CODE_LENGTH) {
            throw new IllegalArgumentException("scenarioCode must not exceed " + MAX_SCENARIO_CODE_LENGTH + " characters");
        }
        String period = requireText("accountingPeriod", cmd.accountingPeriod());
        String mappedJobName = defaultJobNameFor(scenario);
        if (cmd.jobName() != null && !mappedJobName.equals(requireText("jobName", cmd.jobName()))) {
            throw new IllegalArgumentException("jobName '" + cmd.jobName() + "' is not the configured job for scenario '"
                    + scenario + "'; expected '" + mappedJobName + "' so rerun remains reproducible");
        }
        Job job = resolveJob(mappedJobName);

        int bucketCount = cmd.bucketCount() != null ? cmd.bucketCount() : defaultBucketCount;
        if (bucketCount < 1 || bucketCount > MAX_BUCKET_COUNT) {
            throw new IllegalArgumentException("bucketCount must be between 1 and " + MAX_BUCKET_COUNT);
        }
        LocalDate date = parsePeriod(period);
        Instant windowFrom = cmd.matchWindowFrom() != null ? cmd.matchWindowFrom()
                : date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowTo = cmd.matchWindowTo() != null ? cmd.matchWindowTo()
                : date.plusDays(1).atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC);
        Instant cutoff = cmd.cutoffTime() != null ? cmd.cutoffTime()
                : date.atTime(LocalTime.of(23, 0, 0)).toInstant(ZoneOffset.UTC);
        if (windowFrom.isAfter(windowTo)) {
            throw new IllegalArgumentException("matchWindowFrom must not be after matchWindowTo");
        }
        if (cutoff.isBefore(windowFrom) || cutoff.isAfter(windowTo)) {
            throw new IllegalArgumentException("cutoffTime must fall inside the match window");
        }

        int seq = seqRepo.nextSequence(scenario, period);
        String runId = buildRunId(tenantId, scenario, period, seq);
        ReconJobContext ctx = new ReconJobContext(runId, tenantId, scenario, period, seq, cutoff, windowFrom, windowTo,
                bucketCount, 1L);
        return run(job, ctx);
    }

    /** 重跑既有 Run (同 runId, 新 attempt): 复用 Run 的窗口/桶数/序号, 走同一 Job 重算。 */
    public LaunchResult rerun(String runId) {
        ReconRun run = runs.find(runId).orElseThrow(() -> new NotFoundException("run not found: " + runId));
        Job job = resolveJob(defaultJobNameFor(run.scenarioCode()));
        long attempt = System.currentTimeMillis(); // 新 attempt → 新 JobInstance (业务重跑)
        ReconJobContext ctx = new ReconJobContext(runId, run.tenantId(), run.scenarioCode(), run.accountingPeriod(),
                run.sequenceNo(), run.cutoffTime(), run.matchWindowFrom(), run.matchWindowTo(),
                run.bucketCount(), attempt);
        return run(job, ctx);
    }

    private LaunchResult run(Job job, ReconJobContext ctx) {
        try {
            JobExecution exec = jobLauncher.run(job, ctx.toJobParameters());
            return new LaunchResult(ctx.runId(), ctx.tenantId(), ctx.sequenceNo(), exec.getStatus().toString(), exec.getId());
        } catch (JobExecutionAlreadyRunningException | JobInstanceAlreadyCompleteException
                 | JobRestartException | JobParametersInvalidException e) {
            throw new IllegalStateException("failed to launch job for run " + ctx.runId() + ": " + e.getMessage(), e);
        }
    }

    private Job resolveJob(String jobName) {
        Job job = jobs.get(jobName);
        if (job == null) {
            throw new IllegalArgumentException("unknown jobName '" + jobName + "'; available = " + jobs.keySet());
        }
        return job;
    }

    /**
     * 场景→Job 路由(首发与重跑同一确定性映射,保重跑可复现)。B4:
     * <ul>
     *   <li>内置 {@code defaultScenarioCode}(MARKETING_3WAY)→ 硬编码 {@code marketingThreeWayJob}(既有行为不变);</li>
     *   <li>其它 code:须在配置存储中<b>存在且启用</b>,且段数 == {@link GenericReconJobConfig#EXPECTED_SEGMENTS}
     *       → 通用引擎 {@code genericReconJob};否则 fail-fast(不静默跑错 Job)。</li>
     * </ul>
     */
    private String defaultJobNameFor(String scenarioCode) {
        if (defaultScenarioCode.equals(scenarioCode)) {
            return defaultJobName;
        }
        if (EntitlementFulfillmentScenario.SCENARIO_CODE.equals(scenarioCode)) {
            return entitlementJobName;
        }
        if (!scenarios.isRunnable(scenarioCode)) {
            throw new IllegalArgumentException("unsupported scenarioCode '" + scenarioCode
                    + "'; not the built-in '" + defaultScenarioCode + "' and not an enabled config-defined scenario");
        }
        AssembledScenario assembled = scenarios.assemble(scenarioCode);
        if (BenefitCashThreeWayScenario.SCENARIO_CODE.equals(scenarioCode)) {
            validateTenantScopedBenefitSources(assembled);
        }
        int segments = assembled.segments().size();
        if (segments != GenericReconJobConfig.EXPECTED_SEGMENTS) {
            throw new IllegalArgumentException("config scenario '" + scenarioCode + "' has " + segments
                    + " segments; genericReconJob handles " + GenericReconJobConfig.EXPECTED_SEGMENTS
                    + "-segment scenarios only");
        }
        return genericJobName;
    }

    /**
     * 现金权益场景即使被管理台改过，也必须保留数据库侧 tenant/window 谓词；缺任一项就拒绝发起，
     * 防止配置误操作退化成全租户、全历史扫描。
     */
    private static void validateTenantScopedBenefitSources(AssembledScenario scenario) {
        scenario.segments().forEach(segment -> java.util.List.of(segment.leftSource(), segment.rightSource())
                .forEach(source -> {
                    if (!"db".equals(source.sourceType())
                            || !"tenant_id".equals(source.params().get("tenantColumn"))
                            || isBlank(source.params().get("windowTimeColumn"))) {
                        throw new IllegalArgumentException("benefit cash source '"
                                + source.params().getOrDefault("table", "unknown")
                                + "' must enforce db tenantColumn=tenant_id and windowTimeColumn");
                    }
                }));
    }

    /** 新租户 Run 用租户摘要隔离主键；legacy 保持原 runId 格式以兼容已有链接。 */
    private static String buildRunId(String tenantId, String scenario, String period, int seq) {
        String prefix = RunKey.LEGACY_TENANT.equals(tenantId) ? "" : tenantHash(tenantId) + ":";
        String runId = prefix + scenario + ":" + period + ":" + seq;
        if (runId.length() > 64) {
            throw new IllegalArgumentException("derived runId too long (>64): " + runId);
        }
        return runId;
    }

    private static String tenantHash(String tenantId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(tenantId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static LocalDate parsePeriod(String period) {
        try {
            return LocalDate.parse(period); // 日账期 YYYY-MM-DD (A5)
        } catch (DateTimeParseException bad) {
            throw new IllegalArgumentException("accountingPeriod must be ISO date YYYY-MM-DD: " + period, bad);
        }
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 发起指令 (REST 请求体 / scheduler 参数); 窗口/cutoff/桶数可空, 空则由账期派生默认。 */
    public record LaunchCommand(
            String scenarioCode,
            String accountingPeriod,
            String jobName,
            Integer bucketCount,
            Instant cutoffTime,
            Instant matchWindowFrom,
            Instant matchWindowTo,
            String tenantId) {

        public LaunchCommand(String scenarioCode, String accountingPeriod, String jobName, Integer bucketCount,
                             Instant cutoffTime, Instant matchWindowFrom, Instant matchWindowTo) {
            this(scenarioCode, accountingPeriod, jobName, bucketCount, cutoffTime, matchWindowFrom,
                    matchWindowTo, null);
        }
    }

    /** 发起结果 (纯数据, 无批类型): runId + 序号 + Batch 执行态 + 执行 id。 */
    public record LaunchResult(String runId, String tenantId, int sequenceNo, String status, Long jobExecutionId) {
    }
}
