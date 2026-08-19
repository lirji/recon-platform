package com.lrj.recon.batch.config;

import com.lrj.recon.batch.job.BucketGroupReader;
import com.lrj.recon.batch.job.BucketPartitioner;
import com.lrj.recon.batch.job.EvaluateProcessor;
import com.lrj.recon.batch.job.EvaluatedGroup;
import com.lrj.recon.batch.job.MatchEvaluateWriter;
import com.lrj.recon.batch.job.PartitionFailureGate;
import com.lrj.recon.batch.job.ReconJobContext;
import com.lrj.recon.batch.job.ReconJobMetricsListener;
import com.lrj.recon.batch.job.SegmentStampListener;
import com.lrj.recon.batch.job.SkewConfigGuardListener;
import com.lrj.recon.batch.job.SkewDetector;
import com.lrj.recon.batch.job.SourceAdapterItemReader;
import com.lrj.recon.batch.job.StagingRecordWriter;
import com.lrj.recon.batch.job.StandardizeProcessor;
import com.lrj.recon.batch.persistence.JdbcRecordRejectStore;
import com.lrj.recon.batch.service.ConfigScenarioService;
import com.lrj.recon.core.application.port.out.ConservationPartialRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyRepository;
import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.spi.SourceAdapter;
import com.lrj.recon.core.spi.SourceReadContext;
import com.lrj.recon.handler.DiscrepancyHandlerChain;
import com.lrj.recon.scenario.SegmentDef;
import com.lrj.recon.scenario.dsl.AssembledScenario;
import com.lrj.recon.scenario.dsl.GenericScenarioAssembler;
import com.lrj.recon.scenario.dsl.MarketingThreeWayDefinition;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;

/**
 * B4 Phase 3b · <b>通用执行引擎</b>:据每 run 的场景(按 {@code jobParameters['scenarioCode']} 经
 * {@link ConfigScenarioService} 从配置存储装配的 {@link AssembledScenario})逐段动态编排
 * {@code prepare → (load → matchEvaluate)×N → report → convergence → alertRelay}。<b>不改代码接入新场景</b>的执行侧。
 *
 * <p><b>同一个 {@code genericReconJob} 跑任意同形态(段数一致)场景</b>:step 结构在启动期按内置种子的段数固定
 * (本 MVP = {@value #EXPECTED_SEGMENTS} 段),step 名按<b>段序号</b>命名;{@link SegmentStampListener} 在 load/manager
 * step 的 beforeStep 写 {@code segmentIndex},共享 @StepScope {@code generic*} 组件据 (scenarioCode, segmentIndex/segmentId)
 * 从<b>本 run 场景</b>解析 SegmentDef。worker 的 segmentId/scenarioCode 由 partitioner 写各分片上下文(既有机制)。
 * 发起时 {@code ReconLaunchService} 校验目标场景段数 == 本 job 形态,否则 fail-fast。
 *
 * <p><b>附加式、零回归</b>:完全不动 {@code marketingThreeWayJob}/{@code reconciliationJob};MARKETING_3WAY 仍走既有
 * 硬编码 job(由 parity 测试证明二者等价),新配置场景走本通用引擎。
 */
@Configuration
public class GenericReconJobConfig {

    /** 本通用引擎的固定段形态(= 内置三方种子段数)。发起时校验目标场景段数与此一致。 */
    public static final int EXPECTED_SEGMENTS = 2;

    private static final int LOAD_CHUNK = 500;
    private static final int DEFAULT_MATCH_CHUNK = 100;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final ReconRecordRepository records;
    private final DiscrepancyRepository discrepancies;
    private final ConservationPartialRepository partials;
    private final JdbcRecordRejectStore rejectStore;
    private final SourceAdapter sourceAdapter;
    private final ConfigScenarioService scenarios;
    private final int segmentCount;

    public GenericReconJobConfig(JobRepository jobRepository,
                                 PlatformTransactionManager txManager,
                                 ReconRecordRepository records,
                                 DiscrepancyRepository discrepancies,
                                 ConservationPartialRepository partials,
                                 JdbcRecordRejectStore rejectStore,
                                 SourceAdapter sourceAdapter,
                                 ConfigScenarioService scenarios) {
        this.jobRepository = jobRepository;
        this.txManager = txManager;
        this.records = records;
        this.discrepancies = discrepancies;
        this.partials = partials;
        this.rejectStore = rejectStore;
        this.sourceAdapter = sourceAdapter;
        this.scenarios = scenarios;
        // step 结构段数在启动期固定(按内置种子形态);运行期场景须匹配此段数(ReconLaunchService 校验)。
        int seedSegments = GenericScenarioAssembler.assemble(MarketingThreeWayDefinition.seed()).segments().size();
        this.segmentCount = seedSegments == EXPECTED_SEGMENTS ? EXPECTED_SEGMENTS : seedSegments;
    }

    // ==================== Job(据固定段形态动态编排,运行期按 scenarioCode 解析) ====================

    @Bean
    public Job genericReconJob(Step prepareRunStep, Step reportStep, Step convergenceStep, Step alertRelayStep,
                               ReconJobMetricsListener jobMetricsListener,
                               SkewConfigGuardListener skewConfigGuardListener,
                               SourceAdapterItemReader genericSourceReader,
                               StandardizeProcessor genericStandardizeProcessor,
                               StagingRecordWriter stagingWriter,
                               BucketGroupReader genericBucketGroupReader,
                               EvaluateProcessor genericEvaluateProcessor,
                               MatchEvaluateWriter genericMatchEvaluateWriter,
                               BucketPartitioner genericBucketPartitioner,
                               TaskExecutor reconPartitionTaskExecutor,
                               @Value("${recon.partition.pool-size:4}") int poolSize,
                               @Value("${recon.match.chunk-size:" + DEFAULT_MATCH_CHUNK + "}") int matchChunk) {
        SimpleJobBuilder flow = new JobBuilder("genericReconJob", jobRepository)
                .listener(skewConfigGuardListener)
                .listener(jobMetricsListener)
                .start(prepareRunStep);
        for (int i = 0; i < segmentCount; i++) {
            Step load = loadStep(i, genericSourceReader, genericStandardizeProcessor, stagingWriter);
            Step match = matchEvaluateStep(i, genericBucketGroupReader, genericEvaluateProcessor,
                    genericMatchEvaluateWriter, genericBucketPartitioner, reconPartitionTaskExecutor, poolSize, matchChunk);
            flow = flow.next(load).next(match);
        }
        return flow.next(reportStep).next(convergenceStep).next(alertRelayStep).build();
    }

    private Step loadStep(int index, SourceAdapterItemReader reader,
                          StandardizeProcessor processor, StagingRecordWriter writer) {
        return new StepBuilder("gen-seg" + index + "-load", jobRepository)
                .<ReconRecord, ReconRecord>chunk(LOAD_CHUNK, txManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener(new SegmentStampListener(index))
                .build();
    }

    private Step matchEvaluateStep(int index, BucketGroupReader reader, EvaluateProcessor processor,
                                   MatchEvaluateWriter writer, BucketPartitioner partitioner,
                                   TaskExecutor taskExecutor, int poolSize, int matchChunk) {
        String workerName = "gen-seg" + index + "-worker";
        Step worker = new StepBuilder(workerName, jobRepository)
                .<MatchGroup, EvaluatedGroup>chunk(matchChunk, txManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
        return new StepBuilder("gen-seg" + index + "-match", jobRepository)
                .partitioner(workerName, partitioner)
                .step(worker)
                .taskExecutor(taskExecutor)
                .gridSize(poolSize)
                .listener(new SegmentStampListener(index))
                .build();
    }

    // ==================== @StepScope 通用组件(每 run 按 scenarioCode 解析) ====================

    @Bean
    @StepScope
    public SourceAdapterItemReader genericSourceReader(
            ReconJobContext ctx,
            @Value("#{stepExecutionContext['segmentIndex']}") Integer segmentIndex) {
        SegmentDef def = segmentByIndex(ctx.scenarioCode(), segmentIndex);
        SourceReadContext left = new SourceReadContext(ctx.runId(), def.segmentId(), Side.LEFT,
                def.spec().leftRole(), ctx.bucketCount(), def.leftSource());
        SourceReadContext right = new SourceReadContext(ctx.runId(), def.segmentId(), Side.RIGHT,
                def.spec().rightRole(), ctx.bucketCount(), def.rightSource());
        return new SourceAdapterItemReader(sourceAdapter, rejectStore, List.of(left, right));
    }

    @Bean
    @StepScope
    public StandardizeProcessor genericStandardizeProcessor(
            ReconJobContext ctx,
            @Value("#{stepExecutionContext['segmentIndex']}") Integer segmentIndex) {
        AssembledScenario scenario = scenarios.assemble(ctx.scenarioCode());
        SegmentDef def = segmentByIndex(scenario, segmentIndex);
        return new StandardizeProcessor(scenario.extractor(), def.spec(), ctx.bucketCount());
    }

    @Bean
    @StepScope
    public BucketGroupReader genericBucketGroupReader(
            @Value("#{stepExecutionContext['runId']}") String runId,
            @Value("#{stepExecutionContext['segmentId']}") String segmentId,
            @Value("#{stepExecutionContext['bucket']}") Integer bucket,
            @Value("#{stepExecutionContext['subIndex']}") Integer subIndex,
            @Value("#{stepExecutionContext['subFanout']}") Integer subFanout) {
        return new BucketGroupReader(records, runId, segmentId, bucket, subIndex, subFanout);
    }

    @Bean
    @StepScope
    public EvaluateProcessor genericEvaluateProcessor(
            EvaluatorResolver evaluatorResolver,
            @Value("#{stepExecutionContext['runId']}") String runId,
            @Value("#{stepExecutionContext['segmentId']}") String segmentId,
            @Value("#{stepExecutionContext['scenarioCode']}") String scenarioCode,
            @Value("#{stepExecutionContext['accountingPeriod']}") String accountingPeriod,
            @Value("#{stepExecutionContext['matchWindowFromEpochMs']}") Long windowFrom,
            @Value("#{stepExecutionContext['matchWindowToEpochMs']}") Long windowTo) {
        SegmentDef def = segmentById(scenarioCode, segmentId);
        EvaluationContext evalCtx = EvaluationContext.fromSegment(def.spec())
                .runId(runId)
                .scenarioCode(scenarioCode)
                .accountingPeriod(accountingPeriod)
                .matchWindowFrom(Instant.ofEpochMilli(windowFrom))
                .matchWindowTo(Instant.ofEpochMilli(windowTo))
                .build();
        return new EvaluateProcessor(evaluatorResolver.resolve(def.rule().evaluatorType()), def.rule(), evalCtx);
    }

    @Bean
    @StepScope
    public MatchEvaluateWriter genericMatchEvaluateWriter(
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

    @Bean
    @StepScope
    public BucketPartitioner genericBucketPartitioner(
            SkewDetector skewDetector, SubBucketPolicy subBucketPolicy,
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['scenarioCode']}") String scenarioCode,
            @Value("#{jobParameters['accountingPeriod']}") String accountingPeriod,
            @Value("#{jobParameters['matchWindowFromEpochMs']}") Long windowFrom,
            @Value("#{jobParameters['matchWindowToEpochMs']}") Long windowTo,
            @Value("#{jobParameters['bucketCount']}") Long bucketCount,
            @Value("#{stepExecutionContext['segmentIndex']}") Integer segmentIndex) {
        SegmentDef def = segmentByIndex(scenarioCode, segmentIndex);
        return new BucketPartitioner(skewDetector, def.segmentId(), runId, scenarioCode, accountingPeriod,
                windowFrom, windowTo, bucketCount.intValue(), subBucketPolicy.enabled(), subBucketPolicy.fanout());
    }

    // ---- 解析 helper(每 run 按 scenarioCode 装配) ----

    private SegmentDef segmentByIndex(String scenarioCode, Integer index) {
        return segmentByIndex(scenarios.assemble(scenarioCode), index);
    }

    private SegmentDef segmentByIndex(AssembledScenario scenario, Integer index) {
        List<SegmentDef> segs = scenario.segments();
        if (index == null || index < 0 || index >= segs.size()) {
            throw new IllegalStateException("segmentIndex " + index + " out of range for scenario " + scenario.code()
                    + " (" + segs.size() + " segments)");
        }
        return segs.get(index);
    }

    private SegmentDef segmentById(String scenarioCode, String segmentId) {
        return scenarios.assemble(scenarioCode).segments().stream()
                .filter(s -> s.segmentId().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no segment " + segmentId + " in scenario " + scenarioCode));
    }
}
