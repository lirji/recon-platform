package com.lrj.recon.workflow.flowable;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * B5 · Flowable 引擎门面(封装 {@code org.flowable} 与引擎生命周期):按 JDBC 连接参数建<b>独立引擎 DB</b> 的
 * ProcessEngine(与业务 recon DB 解耦,避免测试 H2 MySQL 模式与 Flowable H2 DDL 的 IDENTITY 冲突;也不新增
 * Spring {@code DataSource} bean 触发自动配置退避)、部署冲正审批 BPMN、注册结束监听器,对外暴露
 * {@link ReversalApprovalWorkflow}。终局状态经 {@link ReversalDecisionSink} 回写业务 recon DB。组合根经此类装配,
 * <b>不直接 import org.flowable</b>(recon-batch ArchUnit 门禁)。启用时 Flowable 自建 {@code ACT_*}(默认关不付代价)。
 */
public final class ReversalWorkflowEngine implements AutoCloseable {

    private static final String BPMN = "processes/reversal-approval.bpmn20.xml";

    private final ProcessEngine engine;
    private final ReversalApprovalWorkflow workflow;

    private ReversalWorkflowEngine(ProcessEngine engine, ReversalApprovalWorkflow workflow) {
        this.engine = engine;
        this.workflow = workflow;
    }

    /** 按 JDBC 参数建独立引擎 DB、部署 BPMN;终局决定经 {@code sink} 回写业务 recon DB 的冲正状态。 */
    public static ReversalWorkflowEngine create(String jdbcUrl, String jdbcDriver, String jdbcUsername,
                                                String jdbcPassword, ReversalDecisionSink sink) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(sink, "sink");

        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setJdbcDriver(jdbcDriver);
        cfg.setJdbcUsername(jdbcUsername);
        cfg.setJdbcPassword(jdbcPassword == null ? "" : jdbcPassword);
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        Map<Object, Object> beans = new HashMap<>();
        beans.put(ReversalStatusListener.BEAN_NAME, new ReversalStatusListener(sink));
        cfg.setBeans(beans);

        ProcessEngine engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().name("reversal-approval")
                .addClasspathResource(BPMN).deploy();
        return new ReversalWorkflowEngine(engine,
                new ReversalApprovalWorkflow(engine.getRuntimeService(), engine.getTaskService()));
    }

    public ReversalApprovalWorkflow workflow() {
        return workflow;
    }

    @Override
    public void close() {
        engine.close();
    }
}
