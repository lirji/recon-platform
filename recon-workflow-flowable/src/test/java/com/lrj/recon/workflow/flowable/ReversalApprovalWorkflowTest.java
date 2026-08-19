package com.lrj.recon.workflow.flowable;

import com.lrj.recon.core.domain.model.ReversalStatus;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B5 冲正审批工作流隔离验证:内存 Flowable 引擎 + 部署 BPMN + 走 submit→listPending→decide 全环,
 * 断言 approve→CONFIRMED / reject→DISCARDED 经监听器落地 sink。零 Spring、零应用 DataSource。
 */
class ReversalApprovalWorkflowTest {

    private record Decision(String reversalId, ReversalStatus status, String operator, String note) {
    }

    private ProcessEngine engine;
    private ReversalApprovalWorkflow workflow;
    private final List<Decision> captured = new ArrayList<>();

    @BeforeEach
    void setUp() {
        captured.clear();
        ReversalDecisionSink sink = (id, status, op, note) -> captured.add(new Decision(id, status, op, note));
        Map<Object, Object> beans = new HashMap<>();
        beans.put(ReversalStatusListener.BEAN_NAME, new ReversalStatusListener(sink));

        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:flowable-test-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        cfg.setJdbcDriver("org.h2.Driver");
        cfg.setJdbcUsername("sa");
        cfg.setJdbcPassword("");
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        cfg.setBeans(beans);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment()
                .addClasspathResource("processes/reversal-approval.bpmn20.xml")
                .deploy();
        workflow = new ReversalApprovalWorkflow(engine.getRuntimeService(), engine.getTaskService());
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void approve_confirms_the_reversal() {
        workflow.submit("rev-1");
        List<ReversalApprovalWorkflow.PendingApproval> pending = workflow.listPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).reversalId()).isEqualTo("rev-1");

        workflow.decide(pending.get(0).taskId(), true, "alice", "金额核对无误");

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).reversalId()).isEqualTo("rev-1");
        assertThat(captured.get(0).status()).isEqualTo(ReversalStatus.CONFIRMED);
        assertThat(captured.get(0).operator()).isEqualTo("alice");
        assertThat(captured.get(0).note()).isEqualTo("金额核对无误");
        assertThat(workflow.listPending()).isEmpty();
    }

    @Test
    void reject_discards_the_reversal() {
        workflow.submit("rev-2");
        String taskId = workflow.listPending().get(0).taskId();

        workflow.decide(taskId, false, "bob", "疑似重复冲正");

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).status()).isEqualTo(ReversalStatus.DISCARDED);
        assertThat(captured.get(0).operator()).isEqualTo("bob");
        assertThat(captured.get(0).note()).isEqualTo("疑似重复冲正");
    }

    @Test
    void null_note_does_not_break_completion() {
        // 评审 M1: note=null 时 Map.of 会 NPE, 已在 decide 做 null 合并; 空审批意见落地为 null (COALESCE 不覆盖)。
        workflow.submit("rev-3");
        String taskId = workflow.listPending().get(0).taskId();

        workflow.decide(taskId, true, "carol", null);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).status()).isEqualTo(ReversalStatus.CONFIRMED);
        assertThat(captured.get(0).note()).isNull();
    }

    @Test
    void unknown_task_fails_fast() {
        assertThatThrownBy(() -> workflow.decide("no-such-task", true, "x", "n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
