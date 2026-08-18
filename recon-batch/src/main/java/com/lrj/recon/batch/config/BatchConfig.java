package com.lrj.recon.batch.config;

import com.lrj.recon.batch.alert.AlertRelayService;
import com.lrj.recon.batch.job.AlertRelayTasklet;
import com.lrj.recon.batch.job.BucketGroupReader;
import com.lrj.recon.batch.job.BucketPartitioner;
import com.lrj.recon.batch.job.DispositionConvergenceTasklet;
import com.lrj.recon.batch.job.EvaluateProcessor;
import com.lrj.recon.batch.job.EvaluatedGroup;
import com.lrj.recon.batch.job.MatchEvaluateWriter;
import com.lrj.recon.batch.job.PartitionFailureGate;
import com.lrj.recon.batch.job.PrepareRunTasklet;
import com.lrj.recon.batch.job.ReconJobContext;
import com.lrj.recon.batch.job.ReconRerunService;
import com.lrj.recon.batch.job.ReportTasklet;
import com.lrj.recon.batch.job.SkewDetector;
import com.lrj.recon.batch.job.SourceAdapterItemReader;
import com.lrj.recon.batch.job.StagingRecordWriter;
import com.lrj.recon.batch.job.StandardizeProcessor;
import com.lrj.recon.batch.job.StepFailureGate;
import com.lrj.recon.batch.persistence.JdbcRecordRejectStore;
import com.lrj.recon.batch.service.DispositionConvergenceService;
import com.lrj.recon.handler.DiscrepancyHandlerChain;
import com.lrj.recon.core.application.port.out.ConservationPartialRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyRepository;
import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.application.port.out.ReconReportRepository;
import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.service.ConservationMerger;
import com.lrj.recon.core.domain.service.ExactEvaluator;
import com.lrj.recon.core.spi.SourceAdapter;
import com.lrj.recon.core.spi.SourceReadContext;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;

/**
 * M3 分桶并行对账 Job 装配 (设计 §6/§11 M3): {@code reconciliationJob} =
 * Step0 prepareRunStep (tasklet) → Step1 loadStep (chunk) → Step2 matchEvaluateStep (<b>partitioned</b>)
 * → Step3 reportStep (tasklet, 汇总局部守恒)。JobRepository / JobLauncher / PlatformTransactionManager 由
 * Spring Boot batch autoconfig 提供; <b>不用 @EnableBatchProcessing</b> (会关掉 Boot 自动装配)。
 *
 * <p><b>Step2 partitioned</b>: {@link BucketPartitioner} 造 {@code 0..N-1} 个 partition (每个绑定一个 bucket,
 * 热点 bucket 可选拆二级 sub-bucket), {@code TaskExecutorPartitionHandler}(内建) 用<b>有界线程池</b>并行跑
 * worker step {@code matchEvaluateWorkerStep}; 每 partition 用 per-bucket 游标 (idx_merge 有序、免 filesort)、
 * 独立事务与 accumulator (无共享可变状态) 流式判差 + 单遍累计守恒 → JobRepository 存 partition 级 checkpoint,
 * 断点续跑只重跑未完成 partition。
 *
 * <p>worker step 的 @StepScope 组件<b>只从 {@code stepExecutionContext} 取运行时上下文</b> (partitioner 已写入),
 * 不依赖 @JobScope —— 避免并行 worker 线程上解析 @JobScope 失败。主线程上的 Step (prepare/load/report) 仍用
 * @JobScope {@link ReconJobContext}。
 */
@Configuration
public class BatchConfig {

    private static final int LOAD_CHUNK = 500;
    /** matchEvaluate worker 每 chunk 组数默认值; 可经 {@code recon.match.chunk-size} 覆盖 (#7: 便于测多 chunk 跨界)。 */
    private static final int DEFAULT_MATCH_CHUNK = 100;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final ReconRunRepository runs;
    private final ReconRecordRepository records;
    private final DiscrepancyRepository discrepancies;
    private final ReconReportRepository reports;
    private final ConservationPartialRepository partials;
    private final ReconRerunService rerunService;
    private final JdbcRecordRejectStore rejectStore;
    private final SourceAdapter sourceAdapter;
    private final SegmentPlan plan;

    public BatchConfig(JobRepository jobRepository,
                       PlatformTransactionManager txManager,
                       ReconRunRepository runs,
                       ReconRecordRepository records,
                       DiscrepancyRepository discrepancies,
                       ReconReportRepository reports,
                       ConservationPartialRepository partials,
                       ReconRerunService rerunService,
                       JdbcRecordRejectStore rejectStore,
                       SourceAdapter sourceAdapter,
                       SegmentPlan plan) {
        this.jobRepository = jobRepository;
        this.txManager = txManager;
        this.runs = runs;
        this.records = records;
        this.discrepancies = discrepancies;
        this.reports = reports;
        this.partials = partials;
        this.rerunService = rerunService;
        this.rejectStore = rejectStore;
        this.sourceAdapter = sourceAdapter;
        this.plan = plan;
    }

    // ==================== Job ====================

    @Bean
    public Job reconciliationJob(Step prepareRunStep, Step loadStep, Step matchEvaluateStep, Step reportStep,
                                 Step convergenceStep, Step alertRelayStep) {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(prepareRunStep)
                .next(loadStep)
                .next(matchEvaluateStep)
                .next(reportStep)
                .next(convergenceStep)   // M5 A1 收敛 (re-link / STALE), 在报表后、告警前
                .next(alertRelayStep)    // M5 Step4 告警中继 (批后, 出 chunk 事务)
                .build();
    }

    // ==================== Step0 prepareRunStep ====================

    @Bean
    public Step prepareRunStep(PrepareRunTasklet prepareRunTasklet) {
        return new StepBuilder("prepareRunStep", jobRepository)
                .tasklet(prepareRunTasklet, txManager)
                .build();
    }

    @Bean
    @StepScope
    public PrepareRunTasklet prepareRunTasklet(ReconJobContext ctx) {
        return new PrepareRunTasklet(runs, rerunService, ctx);
    }

    // ==================== Step1 loadStep ====================

    @Bean
    public Step loadStep(SourceAdapterItemReader sourceReader,
                         StandardizeProcessor standardizeProcessor,
                         StagingRecordWriter stagingWriter) {
        return new StepBuilder("loadStep", jobRepository)
                .<ReconRecord, ReconRecord>chunk(LOAD_CHUNK, txManager)
                .reader(sourceReader)
                .processor(standardizeProcessor)
                .writer(stagingWriter)
                .build();
    }

    @Bean
    @StepScope
    public SourceAdapterItemReader sourceReader(ReconJobContext ctx) {
        SourceReadContext left = new SourceReadContext(ctx.runId(), plan.segmentId(), Side.LEFT,
                plan.spec().leftRole(), ctx.bucketCount(), plan.leftSource());
        SourceReadContext right = new SourceReadContext(ctx.runId(), plan.segmentId(), Side.RIGHT,
                plan.spec().rightRole(), ctx.bucketCount(), plan.rightSource());
        return new SourceAdapterItemReader(sourceAdapter, rejectStore, List.of(left, right));
    }

    @Bean
    @StepScope
    public StandardizeProcessor standardizeProcessor(ReconJobContext ctx) {
        return new StandardizeProcessor(plan.extractor(), plan.spec(), ctx.bucketCount());
    }

    @Bean
    public StagingRecordWriter stagingWriter() {
        return new StagingRecordWriter(records);
    }

    // ==================== Step2 matchEvaluateStep (partitioned) ====================

    /**
     * 分桶并行 manager step (名 {@code matchEvaluateStep}, 与 M2 一致 —— 断点续跑断言按此名计数):
     * partitioner 造分片, 有界线程池并行跑 worker step。用 {@code .step(worker).taskExecutor(...).gridSize(...)}
     * 让 builder 内建 TaskExecutorPartitionHandler (gridSize 仅建议值, 实际分片数由 partitioner 决定)。
     */
    @Bean
    public Step matchEvaluateStep(Step matchEvaluateWorkerStep,
                                  BucketPartitioner bucketPartitioner,
                                  TaskExecutor reconPartitionTaskExecutor,
                                  @Value("${recon.partition.pool-size:4}") int poolSize) {
        return new StepBuilder("matchEvaluateStep", jobRepository)
                .partitioner("matchEvaluateWorkerStep", bucketPartitioner)
                .step(matchEvaluateWorkerStep)
                .taskExecutor(reconPartitionTaskExecutor)
                .gridSize(poolSize)
                .build();
    }

    /** 每 partition (一个 bucket) 的 chunk worker step: 单桶游标归并 → 判差 → 单遍累计守恒 + 幂等落库。 */
    @Bean
    public Step matchEvaluateWorkerStep(BucketGroupReader bucketGroupReader,
                                        EvaluateProcessor evaluateProcessor,
                                        MatchEvaluateWriter matchEvaluateWriter,
                                        @Value("${recon.match.chunk-size:" + DEFAULT_MATCH_CHUNK + "}") int matchChunk) {
        return new StepBuilder("matchEvaluateWorkerStep", jobRepository)
                .<MatchGroup, EvaluatedGroup>chunk(matchChunk, txManager)
                .reader(bucketGroupReader)
                .processor(evaluateProcessor)
                .writer(matchEvaluateWriter)
                .build();
    }

    /** 倾斜检测: 读各 bucket 行数 (经端口), 标热点 bucket。 */
    @Bean
    public SkewDetector skewDetector(@Value("${recon.skew.factor:5.0}") double factor,
                                     @Value("${recon.skew.min-rows:1000}") long minRows) {
        return new SkewDetector(records, factor, minRows);
    }

    /**
     * 有界线程池 (无界队列 + core==max=poolSize → 并发严格 = poolSize; 与连接池上限权衡, 见 application.yml)。
     * {@link ThreadPoolTaskExecutor} 实现 InitializingBean/DisposableBean, 由 Spring 生命周期 initialize/shutdown,
     * 不手工 initialize (避免二次建池)。
     */
    @Bean
    public TaskExecutor reconPartitionTaskExecutor(
            @Value("${recon.partition.pool-size:4}") int poolSize,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int dbPoolSize) {
        // #D 启动护栏: 每 partition worker 峰值持 3 连接 (LEFT/RIGHT 两条流式游标 + chunk 事务),
        // 故 DB 连接池须 >= 3*poolSize + 余量, 否则并行分区会连接饥饿<b>死锁</b>而非报错。
        // pool-size 与 hikari.maximum-pool-size 是两个独立可覆盖项 (RECON_PARTITION_POOL_SIZE / DB_POOL_SIZE),
        // 配错时在此 fail-fast, 而不是运行到一半死锁。
        int required = 3 * poolSize + 2; // +2 给 JobRepository / 汇总步余量
        if (dbPoolSize < required) {
            throw new IllegalStateException(String.format(
                    "连接池配置不足: spring.datasource.hikari.maximum-pool-size=%d < 3*recon.partition.pool-size(%d)+2=%d。"
                    + "每 partition worker 峰值需 3 连接(LEFT/RIGHT 游标+事务); 请增大 DB 池(DB_POOL_SIZE)或减小 "
                    + "并行度(RECON_PARTITION_POOL_SIZE), 以免分桶并行连接饥饿死锁。",
                    dbPoolSize, poolSize, required));
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setThreadNamePrefix("recon-part-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }

    @Bean
    @StepScope
    public BucketPartitioner bucketPartitioner(
            SkewDetector skewDetector,
            SubBucketPolicy subBucketPolicy,
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['scenarioCode']}") String scenarioCode,
            @Value("#{jobParameters['accountingPeriod']}") String accountingPeriod,
            @Value("#{jobParameters['matchWindowFromEpochMs']}") Long windowFrom,
            @Value("#{jobParameters['matchWindowToEpochMs']}") Long windowTo,
            @Value("#{jobParameters['bucketCount']}") Long bucketCount) {
        // #1: sub-bucket 开关/fanout 从 SubBucketPolicy 读 (@StepScope 每次 (重)launch 重建 → 读当前值), 使
        // "restart + 配置翻转" 的 shape-flip 场景可测; 生产默认实现从 recon.skew.sub-bucket.* 配置读、不可变。
        return new BucketPartitioner(skewDetector, plan.segmentId(), runId, scenarioCode, accountingPeriod,
                windowFrom, windowTo, bucketCount.intValue(), subBucketPolicy.enabled(), subBucketPolicy.fanout());
    }

    @Bean
    @StepScope
    public BucketGroupReader bucketGroupReader(
            @Value("#{stepExecutionContext['runId']}") String runId,
            @Value("#{stepExecutionContext['segmentId']}") String segmentId,
            @Value("#{stepExecutionContext['bucket']}") Integer bucket,
            @Value("#{stepExecutionContext['subIndex']}") Integer subIndex,
            @Value("#{stepExecutionContext['subFanout']}") Integer subFanout) {
        return new BucketGroupReader(records, runId, segmentId, bucket, subIndex, subFanout);
    }

    @Bean
    @StepScope
    public EvaluateProcessor evaluateProcessor(
            @Value("#{stepExecutionContext['runId']}") String runId,
            @Value("#{stepExecutionContext['scenarioCode']}") String scenarioCode,
            @Value("#{stepExecutionContext['accountingPeriod']}") String accountingPeriod,
            @Value("#{stepExecutionContext['matchWindowFromEpochMs']}") Long windowFrom,
            @Value("#{stepExecutionContext['matchWindowToEpochMs']}") Long windowTo) {
        EvaluationContext evalCtx = EvaluationContext.fromSegment(plan.spec())
                .runId(runId)
                .scenarioCode(scenarioCode)
                .accountingPeriod(accountingPeriod)
                .matchWindowFrom(Instant.ofEpochMilli(windowFrom))
                .matchWindowTo(Instant.ofEpochMilli(windowTo))
                .build();
        return new EvaluateProcessor(new ExactEvaluator(), plan.rule(), evalCtx);
    }

    @Bean
    @StepScope
    public MatchEvaluateWriter matchEvaluateWriter(
            DiscrepancyHandlerChain discrepancyHandlerChain,
            @Value("#{stepExecutionContext['runId']}") String runId,
            @Value("#{stepExecutionContext['segmentId']}") String segmentId,
            @Value("#{stepExecutionContext['bucket']}") Integer bucket,
            @Value("#{stepExecutionContext['subIndex']}") Integer subIndex,
            @Value("#{stepExecutionContext['subFanout']}") Integer subFanout,
            ObjectProvider<PartitionFailureGate> failureGate) {
        PartitionFailureGate gate = failureGate.getIfAvailable(() -> b -> { });
        return new MatchEvaluateWriter(discrepancies, partials, discrepancyHandlerChain, gate,
                runId, segmentId, bucket, subIndex, subFanout);
    }

    // ==================== Step3 reportStep (汇总局部守恒) ====================

    @Bean
    public Step reportStep(ReportTasklet reportTasklet) {
        return new StepBuilder("reportStep", jobRepository)
                .tasklet(reportTasklet, txManager)
                .build();
    }

    @Bean
    @StepScope
    public ReportTasklet reportTasklet(ReconJobContext ctx, ObjectProvider<StepFailureGate> failureGate) {
        StepFailureGate gate = failureGate.getIfAvailable(() -> runId -> { });
        return new ReportTasklet(runs, reports, partials, new ConservationMerger(), ctx, gate);
    }

    // ==================== M5 收敛 + 告警中继 (run 级共享步, marketingThreeWayJob 复用) ====================

    /** A1 重跑收敛步 (报表后): re-link 保持 / 标 STALE 自动关闭。单段与三方 Job 复用同一 bean。 */
    @Bean
    public Step convergenceStep(DispositionConvergenceTasklet convergenceTasklet) {
        return new StepBuilder("convergenceStep", jobRepository)
                .tasklet(convergenceTasklet, txManager)
                .build();
    }

    @Bean
    @StepScope
    public DispositionConvergenceTasklet convergenceTasklet(ReconJobContext ctx,
                                                            DispositionConvergenceService convergenceService) {
        return new DispositionConvergenceTasklet(convergenceService, ctx);
    }

    /** Step4 告警中继步 (批后): 投递 outbox PENDING/补投 FAILED, 出 chunk 事务。单段与三方 Job 复用同一 bean。 */
    @Bean
    public Step alertRelayStep(AlertRelayTasklet alertRelayTasklet) {
        return new StepBuilder("alertRelayStep", jobRepository)
                .tasklet(alertRelayTasklet, txManager)
                .build();
    }

    @Bean
    public AlertRelayTasklet alertRelayTasklet(AlertRelayService alertRelayService) {
        return new AlertRelayTasklet(alertRelayService);
    }

    // ==================== 运行时上下文 (@JobScope, 从 jobParameters 惰性解出) ====================

    // ReconJobContext 是 record (final) 无法 CGLIB 代理, 故用 proxyMode=NO: 它只被<b>主线程</b>上同为 job/step
    // 作用域的组件注入 (prepare/load/report; 注入时 job 作用域已激活), 无需作用域代理。并行 worker 组件<b>不</b>
    // 注入它 (改从 stepExecutionContext 取), 故不涉及 worker 线程上的 @JobScope 解析。
    @Bean
    @Scope(value = "job", proxyMode = ScopedProxyMode.NO)
    public ReconJobContext reconJobContext(
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['scenarioCode']}") String scenarioCode,
            @Value("#{jobParameters['accountingPeriod']}") String accountingPeriod,
            @Value("#{jobParameters['sequenceNo']}") Long sequenceNo,
            @Value("#{jobParameters['cutoffTimeEpochMs']}") Long cutoffEpochMs,
            @Value("#{jobParameters['matchWindowFromEpochMs']}") Long windowFromEpochMs,
            @Value("#{jobParameters['matchWindowToEpochMs']}") Long windowToEpochMs,
            @Value("#{jobParameters['bucketCount']}") Long bucketCount,
            @Value("#{jobParameters['attempt']}") Long attempt) {
        return ReconJobContext.of(runId, scenarioCode, accountingPeriod, sequenceNo,
                cutoffEpochMs, windowFromEpochMs, windowToEpochMs, bucketCount, attempt);
    }
}
