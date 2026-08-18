package com.lrj.recon.batch.config;

import com.lrj.recon.batch.job.BucketGroupReader;
import com.lrj.recon.batch.job.BucketPartitioner;
import com.lrj.recon.batch.job.EvaluateProcessor;
import com.lrj.recon.batch.job.EvaluatedGroup;
import com.lrj.recon.batch.job.MatchEvaluateWriter;
import com.lrj.recon.batch.job.PartitionFailureGate;
import com.lrj.recon.batch.job.ReconJobContext;
import com.lrj.recon.batch.job.SkewDetector;
import com.lrj.recon.batch.job.SourceAdapterItemReader;
import com.lrj.recon.batch.job.StagingRecordWriter;
import com.lrj.recon.batch.job.StandardizeProcessor;
import com.lrj.recon.batch.persistence.JdbcRecordRejectStore;
import com.lrj.recon.core.application.port.out.ConservationPartialRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyRepository;
import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.service.EvaluatorFactory;
import com.lrj.recon.core.spi.SourceAdapter;
import com.lrj.recon.core.spi.SourceReadContext;
import com.lrj.recon.scenario.MarketingThreeWayScenario;
import com.lrj.recon.scenario.SegmentDef;
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
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * M4 营销三方两段桥接对账 Job 装配 (设计 §6/§11 M4): {@code marketingThreeWayJob} =
 * <pre>
 *   prepareRunStep (复用)
 *   → seg1LoadStep → seg1MatchEvaluateStep (partitioned)
 *   → seg2LoadStep → seg2MatchEvaluateStep (partitioned)
 *   → reportStep (复用)
 * </pre>
 * 两段责任链<b>顺序执行</b> (SEG1 营销↔账务 → SEG2 账务↔渠道), 各段独立 load→matchEvaluate、各自落
 * segment 级 {@code recon_report_partial}; 末尾 {@code reportStep} 跨段合并出<b>各段独立</b>的 recon_report
 * (按 segment,currency 双向守恒) 并置终态。账务 spine 在两段用不同描述符投影不同键列 (SEG1 issue_id / SEG2
 * channel_serial_no), 由 {@link MarketingThreeWayScenario} 装配。
 *
 * <p><b>与单段 {@code reconciliationJob} (BatchConfig) 完全隔离</b>: 本类只新增 M4 专属 bean (seg1/seg2/m4 前缀),
 * 复用 BatchConfig 的 {@code prepareRunStep}/{@code reportStep}/{@code stagingWriter}/{@code skewDetector}/
 * {@code reconPartitionTaskExecutor}/{@code subBucketPolicy} 等<b>run 级/段无关</b>组件, 不改动其单段接线 (回归零风险)。
 *
 * <p><b>分桶并行沿用 M3</b>: 两段各有独立 <b>worker step 名</b> (seg1/seg2MatchWorkerStep) 避免 partition 子执行
 * 命名冲突; 三个 @StepScope worker 组件 (reader/processor/writer) 只从 {@code stepExecutionContext} 取上下文
 * (segmentId 由各段 partitioner 写入), 故被两段 worker step 共享、按 segment 正确解析, 无共享可变状态。
 */
@Configuration
public class MarketingThreeWayConfig {

    private static final int LOAD_CHUNK = 500;
    private static final int DEFAULT_MATCH_CHUNK = 100;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager txManager;
    private final ReconRecordRepository records;
    private final DiscrepancyRepository discrepancies;
    private final ConservationPartialRepository partials;
    private final JdbcRecordRejectStore rejectStore;
    private final SourceAdapter sourceAdapter;
    private final MarketingThreeWayScenario scenario;
    private final Map<String, SegmentDef> segmentsById;

    public MarketingThreeWayConfig(JobRepository jobRepository,
                                   PlatformTransactionManager txManager,
                                   ReconRecordRepository records,
                                   DiscrepancyRepository discrepancies,
                                   ConservationPartialRepository partials,
                                   JdbcRecordRejectStore rejectStore,
                                   SourceAdapter sourceAdapter,
                                   @Value("${recon.m4.marketing-table:recon_src_marketing}") String marketingTable,
                                   @Value("${recon.m4.accounting-table:recon_src_accounting}") String accountingTable,
                                   @Value("${recon.m4.channel-table:recon_src_channel}") String channelTable,
                                   // 修复 D: 两段判差规则改为可配 (默认 EXACT 保既有行为)。配 TOLERANCE + 阈值即让该段
                                   // 运行态经 EvaluatorFactory 命中 ToleranceEvaluator (之前硬编码 exact() 使容差成死代码)。
                                   @Value("${recon.scenario.mkt.seg1.evaluator-type:EXACT}") String seg1EvaluatorType,
                                   @Value("${recon.scenario.mkt.seg1.abs-tolerance-minor:0}") long seg1AbsToleranceMinor,
                                   @Value("${recon.scenario.mkt.seg1.ratio-tolerance-bps:0}") int seg1RatioToleranceBps,
                                   @Value("${recon.scenario.mkt.seg2.evaluator-type:EXACT}") String seg2EvaluatorType,
                                   @Value("${recon.scenario.mkt.seg2.abs-tolerance-minor:0}") long seg2AbsToleranceMinor,
                                   @Value("${recon.scenario.mkt.seg2.ratio-tolerance-bps:0}") int seg2RatioToleranceBps) {
        this.jobRepository = jobRepository;
        this.txManager = txManager;
        this.records = records;
        this.discrepancies = discrepancies;
        this.partials = partials;
        this.rejectStore = rejectStore;
        this.sourceAdapter = sourceAdapter;
        this.scenario = MarketingThreeWayScenario.of(new MarketingThreeWayScenario.Config(
                marketingTable, accountingTable, channelTable,
                ruleFrom(seg1EvaluatorType, seg1AbsToleranceMinor, seg1RatioToleranceBps),
                ruleFrom(seg2EvaluatorType, seg2AbsToleranceMinor, seg2RatioToleranceBps)));
        this.segmentsById = Map.of(
                scenario.seg1().segmentId(), scenario.seg1(),
                scenario.seg2().segmentId(), scenario.seg2());
    }

    /**
     * 由配置构造段判差规则。{@code evaluatorType} 默认 EXACT (阈值被忽略, 等价既有 {@link DiscrepancyRule#exact()});
     * TOLERANCE 时 abs/ratio 阈值随 rule 流入 {@link com.lrj.recon.core.domain.service.ToleranceEvaluator}
     * (evaluate 期读 rule); DROOLS 由 {@code EvaluatorFactory} 运行期 fail-fast。非法类型名装配期即 fail-fast。
     */
    private static DiscrepancyRule ruleFrom(String evaluatorType, long absToleranceMinor, int ratioToleranceBps) {
        EvaluatorType type;
        try {
            type = EvaluatorType.valueOf(evaluatorType.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException bad) {
            throw new IllegalArgumentException("unknown recon.scenario.mkt.*.evaluator-type='" + evaluatorType
                    + "' (expected EXACT|TOLERANCE|DROOLS)", bad);
        }
        return DiscrepancyRule.builder()
                .evaluatorType(type)
                .absToleranceMinor(absToleranceMinor)
                .ratioToleranceBps(ratioToleranceBps)
                .build();
    }

    // ==================== Job ====================

    @Bean
    public Job marketingThreeWayJob(Step prepareRunStep,
                                    Step seg1LoadStep, Step seg1MatchEvaluateStep,
                                    Step seg2LoadStep, Step seg2MatchEvaluateStep,
                                    Step reportStep) {
        return new JobBuilder("marketingThreeWayJob", jobRepository)
                .start(prepareRunStep)
                .next(seg1LoadStep)
                .next(seg1MatchEvaluateStep)
                .next(seg2LoadStep)
                .next(seg2MatchEvaluateStep)
                .next(reportStep)
                .build();
    }

    // ==================== SEG1 load ====================

    @Bean
    public Step seg1LoadStep(SourceAdapterItemReader seg1SourceReader,
                             StandardizeProcessor seg1StandardizeProcessor,
                             StagingRecordWriter stagingWriter) {
        return new StepBuilder("seg1LoadStep", jobRepository)
                .<ReconRecord, ReconRecord>chunk(LOAD_CHUNK, txManager)
                .reader(seg1SourceReader)
                .processor(seg1StandardizeProcessor)
                .writer(stagingWriter)
                .build();
    }

    @Bean
    @StepScope
    public SourceAdapterItemReader seg1SourceReader(ReconJobContext ctx) {
        return sourceReaderFor(scenario.seg1(), ctx);
    }

    @Bean
    @StepScope
    public StandardizeProcessor seg1StandardizeProcessor(ReconJobContext ctx) {
        return new StandardizeProcessor(scenario.extractor(), scenario.seg1().spec(), ctx.bucketCount());
    }

    // ==================== SEG2 load ====================

    @Bean
    public Step seg2LoadStep(SourceAdapterItemReader seg2SourceReader,
                             StandardizeProcessor seg2StandardizeProcessor,
                             StagingRecordWriter stagingWriter) {
        return new StepBuilder("seg2LoadStep", jobRepository)
                .<ReconRecord, ReconRecord>chunk(LOAD_CHUNK, txManager)
                .reader(seg2SourceReader)
                .processor(seg2StandardizeProcessor)
                .writer(stagingWriter)
                .build();
    }

    @Bean
    @StepScope
    public SourceAdapterItemReader seg2SourceReader(ReconJobContext ctx) {
        return sourceReaderFor(scenario.seg2(), ctx);
    }

    @Bean
    @StepScope
    public StandardizeProcessor seg2StandardizeProcessor(ReconJobContext ctx) {
        return new StandardizeProcessor(scenario.extractor(), scenario.seg2().spec(), ctx.bucketCount());
    }

    private SourceAdapterItemReader sourceReaderFor(SegmentDef def, ReconJobContext ctx) {
        SourceReadContext left = new SourceReadContext(ctx.runId(), def.segmentId(), Side.LEFT,
                def.spec().leftRole(), ctx.bucketCount(), def.leftSource());
        SourceReadContext right = new SourceReadContext(ctx.runId(), def.segmentId(), Side.RIGHT,
                def.spec().rightRole(), ctx.bucketCount(), def.rightSource());
        return new SourceAdapterItemReader(sourceAdapter, rejectStore, List.of(left, right));
    }

    // ==================== SEG1 / SEG2 matchEvaluate (partitioned) ====================

    @Bean
    public Step seg1MatchEvaluateStep(Step seg1MatchWorkerStep,
                                      BucketPartitioner seg1BucketPartitioner,
                                      TaskExecutor reconPartitionTaskExecutor,
                                      @Value("${recon.partition.pool-size:4}") int poolSize) {
        return new StepBuilder("seg1MatchEvaluateStep", jobRepository)
                .partitioner("seg1MatchWorkerStep", seg1BucketPartitioner)
                .step(seg1MatchWorkerStep)
                .taskExecutor(reconPartitionTaskExecutor)
                .gridSize(poolSize)
                .build();
    }

    @Bean
    public Step seg2MatchEvaluateStep(Step seg2MatchWorkerStep,
                                      BucketPartitioner seg2BucketPartitioner,
                                      TaskExecutor reconPartitionTaskExecutor,
                                      @Value("${recon.partition.pool-size:4}") int poolSize) {
        return new StepBuilder("seg2MatchEvaluateStep", jobRepository)
                .partitioner("seg2MatchWorkerStep", seg2BucketPartitioner)
                .step(seg2MatchWorkerStep)
                .taskExecutor(reconPartitionTaskExecutor)
                .gridSize(poolSize)
                .build();
    }

    /** SEG1 worker step (独立步名避免 partition 子执行命名冲突; 复用 m4* @StepScope 组件, 按 context segmentId 解析)。 */
    @Bean
    public Step seg1MatchWorkerStep(BucketGroupReader m4BucketGroupReader,
                                    EvaluateProcessor m4EvaluateProcessor,
                                    MatchEvaluateWriter m4MatchEvaluateWriter,
                                    @Value("${recon.match.chunk-size:" + DEFAULT_MATCH_CHUNK + "}") int matchChunk) {
        return new StepBuilder("seg1MatchWorkerStep", jobRepository)
                .<MatchGroup, EvaluatedGroup>chunk(matchChunk, txManager)
                .reader(m4BucketGroupReader)
                .processor(m4EvaluateProcessor)
                .writer(m4MatchEvaluateWriter)
                .build();
    }

    @Bean
    public Step seg2MatchWorkerStep(BucketGroupReader m4BucketGroupReader,
                                    EvaluateProcessor m4EvaluateProcessor,
                                    MatchEvaluateWriter m4MatchEvaluateWriter,
                                    @Value("${recon.match.chunk-size:" + DEFAULT_MATCH_CHUNK + "}") int matchChunk) {
        return new StepBuilder("seg2MatchWorkerStep", jobRepository)
                .<MatchGroup, EvaluatedGroup>chunk(matchChunk, txManager)
                .reader(m4BucketGroupReader)
                .processor(m4EvaluateProcessor)
                .writer(m4MatchEvaluateWriter)
                .build();
    }

    @Bean
    @StepScope
    public BucketPartitioner seg1BucketPartitioner(
            SkewDetector skewDetector, SubBucketPolicy subBucketPolicy,
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['scenarioCode']}") String scenarioCode,
            @Value("#{jobParameters['accountingPeriod']}") String accountingPeriod,
            @Value("#{jobParameters['matchWindowFromEpochMs']}") Long windowFrom,
            @Value("#{jobParameters['matchWindowToEpochMs']}") Long windowTo,
            @Value("#{jobParameters['bucketCount']}") Long bucketCount) {
        return new BucketPartitioner(skewDetector, scenario.seg1().segmentId(), runId, scenarioCode,
                accountingPeriod, windowFrom, windowTo, bucketCount.intValue(),
                subBucketPolicy.enabled(), subBucketPolicy.fanout());
    }

    @Bean
    @StepScope
    public BucketPartitioner seg2BucketPartitioner(
            SkewDetector skewDetector, SubBucketPolicy subBucketPolicy,
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['scenarioCode']}") String scenarioCode,
            @Value("#{jobParameters['accountingPeriod']}") String accountingPeriod,
            @Value("#{jobParameters['matchWindowFromEpochMs']}") Long windowFrom,
            @Value("#{jobParameters['matchWindowToEpochMs']}") Long windowTo,
            @Value("#{jobParameters['bucketCount']}") Long bucketCount) {
        return new BucketPartitioner(skewDetector, scenario.seg2().segmentId(), runId, scenarioCode,
                accountingPeriod, windowFrom, windowTo, bucketCount.intValue(),
                subBucketPolicy.enabled(), subBucketPolicy.fanout());
    }

    // ---- 共享 @StepScope worker 组件 (从 stepExecutionContext 取上下文, 按 segmentId 解析) ----

    @Bean
    @StepScope
    public BucketGroupReader m4BucketGroupReader(
            @Value("#{stepExecutionContext['runId']}") String runId,
            @Value("#{stepExecutionContext['segmentId']}") String segmentId,
            @Value("#{stepExecutionContext['bucket']}") Integer bucket,
            @Value("#{stepExecutionContext['subIndex']}") Integer subIndex,
            @Value("#{stepExecutionContext['subFanout']}") Integer subFanout) {
        return new BucketGroupReader(records, runId, segmentId, bucket, subIndex, subFanout);
    }

    @Bean
    @StepScope
    public EvaluateProcessor m4EvaluateProcessor(
            @Value("#{stepExecutionContext['runId']}") String runId,
            @Value("#{stepExecutionContext['segmentId']}") String segmentId,
            @Value("#{stepExecutionContext['scenarioCode']}") String scenarioCode,
            @Value("#{stepExecutionContext['accountingPeriod']}") String accountingPeriod,
            @Value("#{stepExecutionContext['matchWindowFromEpochMs']}") Long windowFrom,
            @Value("#{stepExecutionContext['matchWindowToEpochMs']}") Long windowTo) {
        SegmentDef def = segmentDef(segmentId);
        EvaluationContext evalCtx = EvaluationContext.fromSegment(def.spec())
                .runId(runId)
                .scenarioCode(scenarioCode)
                .accountingPeriod(accountingPeriod)
                .matchWindowFrom(Instant.ofEpochMilli(windowFrom))
                .matchWindowTo(Instant.ofEpochMilli(windowTo))
                .build();
        // 判差器按段规则路由 (EXACT/TOLERANCE; DROOLS fail-fast) —— EvaluatorFactory 单一装配口。
        return new EvaluateProcessor(EvaluatorFactory.create(def.rule().evaluatorType()), def.rule(), evalCtx);
    }

    @Bean
    @StepScope
    public MatchEvaluateWriter m4MatchEvaluateWriter(
            @Value("#{stepExecutionContext['runId']}") String runId,
            @Value("#{stepExecutionContext['segmentId']}") String segmentId,
            @Value("#{stepExecutionContext['bucket']}") Integer bucket,
            @Value("#{stepExecutionContext['subIndex']}") Integer subIndex,
            @Value("#{stepExecutionContext['subFanout']}") Integer subFanout,
            ObjectProvider<PartitionFailureGate> failureGate) {
        PartitionFailureGate gate = failureGate.getIfAvailable(() -> b -> { });
        return new MatchEvaluateWriter(discrepancies, partials, gate, runId, segmentId, bucket, subIndex, subFanout);
    }

    private SegmentDef segmentDef(String segmentId) {
        SegmentDef def = segmentsById.get(segmentId);
        if (def == null) {
            throw new IllegalStateException("no segment definition for segmentId=" + segmentId
                    + " (marketingThreeWayJob expects " + segmentsById.keySet() + ")");
        }
        return def;
    }
}
