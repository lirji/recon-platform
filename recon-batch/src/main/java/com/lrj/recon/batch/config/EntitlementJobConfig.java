package com.lrj.recon.batch.config;

import com.lrj.recon.batch.job.EntitlementReconciliationTasklet;
import com.lrj.recon.batch.job.ReconJobContext;
import com.lrj.recon.batch.service.EntitlementReconStore;
import com.lrj.recon.core.application.port.out.ReconRunRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** 非金额权益专用批作业装配，与金额守恒引擎完全分离。 */
@Configuration
public class EntitlementJobConfig {

    /** 复用准备、收敛和告警步骤，仅替换中间的数量判差步骤。 */
    @Bean
    public Job entitlementFulfillmentJob(JobRepository jobRepository, Step prepareRunStep,
                                         Step entitlementReconciliationStep, Step convergenceStep,
                                         Step alertRelayStep) {
        return new JobBuilder("entitlementFulfillmentJob", jobRepository)
                .start(prepareRunStep)
                .next(entitlementReconciliationStep)
                .next(convergenceStep)
                .next(alertRelayStep)
                .build();
    }

    /** 非金额权益判差步骤在单个事务内推进一页页的有界读取。 */
    @Bean
    public Step entitlementReconciliationStep(JobRepository jobRepository,
                                              PlatformTransactionManager txManager,
                                              EntitlementReconciliationTasklet tasklet) {
        return new StepBuilder("entitlementReconciliationStep", jobRepository)
                .tasklet(tasklet, txManager)
                .build();
    }

    /** StepScope 保证每次 Run 读取自己的 tenant/window 上下文。 */
    @Bean
    @StepScope
    public EntitlementReconciliationTasklet entitlementReconciliationTasklet(
            EntitlementReconStore store, ReconRunRepository runs, ReconJobContext context) {
        return new EntitlementReconciliationTasklet(store, runs, context);
    }
}
