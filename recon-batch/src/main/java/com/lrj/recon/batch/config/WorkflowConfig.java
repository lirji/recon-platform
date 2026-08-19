package com.lrj.recon.batch.config;

import com.lrj.recon.core.application.port.out.ReversalSuggestionRepository;
import com.lrj.recon.workflow.flowable.ReversalApprovalWorkflow;
import com.lrj.recon.workflow.flowable.ReversalDecisionSink;
import com.lrj.recon.workflow.flowable.ReversalWorkflowEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * B5 Flowable 冲正审批装配。整组 bean 仅在 {@code recon.workflow.flowable.enabled=true} 时创建(默认关):
 * 关闭时 Flowable 引擎不加载、无 {@code ACT_*} 表、审批 API 退化 fail-fast,现有测试与行为零影响。
 * 组合根只经 {@link ReversalWorkflowEngine} 门面装配,不直接 import {@code org.flowable}(ArchUnit 门禁)。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "recon.workflow.flowable", name = "enabled", havingValue = "true")
public class WorkflowConfig {

    /** 审批终局落地:CONFIRMED/DISCARDED 只改 reversal_suggestion.status + operator + decision_note(ADR-7)。 */
    @Bean
    public ReversalDecisionSink reversalDecisionSink(ReversalSuggestionRepository reversals) {
        return (reversalId, status, operator, note) -> reversals.updateStatus(reversalId, status, operator, note);
    }

    @Bean(destroyMethod = "close")
    public ReversalWorkflowEngine reversalWorkflowEngine(
            // 独立引擎 DB(默认内存 H2 普通模式,避 MODE=MySQL 与 Flowable H2 DDL 冲突);生产可指向真 MySQL/PG。
            @Value("${recon.workflow.flowable.jdbc-url:jdbc:h2:mem:recon-flowable;DB_CLOSE_DELAY=-1}") String jdbcUrl,
            @Value("${recon.workflow.flowable.jdbc-driver:org.h2.Driver}") String jdbcDriver,
            @Value("${recon.workflow.flowable.jdbc-username:sa}") String jdbcUsername,
            @Value("${recon.workflow.flowable.jdbc-password:}") String jdbcPassword,
            ReversalDecisionSink sink) {
        return ReversalWorkflowEngine.create(jdbcUrl, jdbcDriver, jdbcUsername, jdbcPassword, sink);
    }

    @Bean
    public ReversalApprovalWorkflow reversalApprovalWorkflow(ReversalWorkflowEngine engine) {
        return engine.workflow();
    }
}
