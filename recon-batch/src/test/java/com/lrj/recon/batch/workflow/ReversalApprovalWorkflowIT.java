package com.lrj.recon.batch.workflow;

import com.lrj.recon.core.application.port.out.ReversalSuggestionRepository;
import com.lrj.recon.core.domain.model.ReversalStatus;
import com.lrj.recon.workflow.flowable.ReversalApprovalWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5 · Flowable 冲正审批端到端(门控启用):seed 一条 SUGGESTED 冲正 → 提交审批 → approve/reject →
 * {@code reversal_suggestion.status} 落 CONFIRMED/DISCARDED(经 BPMN 结束监听器 → sink → JdbcStore.updateStatus)。
 * 仅本测试以 {@code recon.workflow.flowable.enabled=true} 启用引擎;默认关时其它 128 测试不加载 Flowable。
 */
@SpringBootTest(properties = "recon.workflow.flowable.enabled=true")
class ReversalApprovalWorkflowIT {

    @Autowired
    ReversalApprovalWorkflow workflow;

    @Autowired
    ReversalSuggestionRepository reversals;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM reversal_suggestion");
    }

    private void seed(String id) {
        jdbc.update("""
                INSERT INTO reversal_suggestion(id, fingerprint, run_id, group_key, suggested_amount_minor,
                    currency, status, idempotency_key, operator, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                id, "F".repeat(64), "run-1", "G1", 1000L, "USD", "SUGGESTED", "idem-" + id, null,
                Timestamp.from(Instant.parse("2026-08-19T10:00:00Z")));
    }

    private String taskFor(String reversalId) {
        return workflow.listPending().stream()
                .filter(p -> reversalId.equals(p.reversalId()))
                .map(ReversalApprovalWorkflow.PendingApproval::taskId)
                .findFirst().orElseThrow(() -> new AssertionError("no pending task for " + reversalId));
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM reversal_suggestion WHERE id = ?", String.class, id);
    }

    private String noteOf(String id) {
        return jdbc.queryForObject("SELECT decision_note FROM reversal_suggestion WHERE id = ?", String.class, id);
    }

    @Test
    void approve_confirms_the_reversal() {
        seed("rev-approve");
        workflow.submit("rev-approve");
        workflow.decide(taskFor("rev-approve"), true, "alice", "金额核对无误");

        assertThat(statusOf("rev-approve")).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT operator FROM reversal_suggestion WHERE id=?", String.class, "rev-approve"))
                .isEqualTo("alice");
        assertThat(noteOf("rev-approve")).isEqualTo("金额核对无误");
    }

    @Test
    void reject_discards_the_reversal() {
        seed("rev-reject");
        workflow.submit("rev-reject");
        workflow.decide(taskFor("rev-reject"), false, "bob", "疑似重复冲正");

        assertThat(statusOf("rev-reject")).isEqualTo("DISCARDED");
        assertThat(noteOf("rev-reject")).isEqualTo("疑似重复冲正");
    }

    @Test
    void execution_keeps_decision_note() {
        // 评审 step3: B3 执行走 3 参 default (note=null) → COALESCE 保留 B5 审批留痕, 不抹掉。
        seed("rev-exec");
        workflow.submit("rev-exec");
        workflow.decide(taskFor("rev-exec"), true, "alice", "已核准执行");
        assertThat(noteOf("rev-exec")).isEqualTo("已核准执行");

        reversals.updateStatus("rev-exec", ReversalStatus.EXECUTED, "system"); // 3 参 default, note=null

        assertThat(statusOf("rev-exec")).isEqualTo("EXECUTED");
        assertThat(noteOf("rev-exec")).isEqualTo("已核准执行");
    }
}
