package com.lrj.recon.workflow.flowable;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * B5 冲正审批工作流封装:对外只暴露「提交审批 / 列待办 / 审批决定」纯 API,把 Flowable RuntimeService/TaskService
 * 藏在内部(调用方零 {@code org.flowable} 耦合)。终局状态由 BPMN 结束监听器经 {@link ReversalDecisionSink} 落地。
 */
public final class ReversalApprovalWorkflow {

    public static final String PROCESS_KEY = "reversalApproval";
    private static final String REVIEW_TASK = "review";

    private final RuntimeService runtimeService;
    private final TaskService taskService;

    public ReversalApprovalWorkflow(RuntimeService runtimeService, TaskService taskService) {
        this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService");
        this.taskService = Objects.requireNonNull(taskService, "taskService");
    }

    /** 提交一条冲正建议进入审批,返回流程实例 id。 */
    public String submit(String reversalId) {
        if (reversalId == null || reversalId.isBlank()) {
            throw new IllegalArgumentException("reversalId must not be blank");
        }
        return runtimeService.startProcessInstanceByKey(PROCESS_KEY, Map.of("reversalId", reversalId)).getId();
    }

    /** 列出待审批任务(含其 reversalId)。 */
    public List<PendingApproval> listPending() {
        return taskService.createTaskQuery()
                .taskDefinitionKey(REVIEW_TASK)
                .includeProcessVariables()
                .orderByTaskCreateTime().asc()
                .list()
                .stream()
                .map(ReversalApprovalWorkflow::toPending)
                .toList();
    }

    /**
     * 审批决定:approved=true→CONFIRMED / false→DISCARDED;{@code note} 为审批意见 (随流程变量传给结束
     * 监听器落地 decision_note);完成后由结束监听器落地状态。{@code operator}/{@code note} 做 null 合并
     * (Flowable {@code Map.of} 不接受 null 值)。
     */
    public void decide(String taskId, boolean approved, String operator, String note) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new IllegalArgumentException("no pending approval task: " + taskId);
        }
        taskService.complete(taskId, Map.of(
                "approved", approved,
                "operator", operator == null ? "" : operator,
                "note", note == null ? "" : note));
    }

    private static PendingApproval toPending(Task t) {
        Object reversalId = t.getProcessVariables().get("reversalId");
        Instant created = t.getCreateTime() == null ? null : t.getCreateTime().toInstant();
        return new PendingApproval(t.getId(), reversalId == null ? null : reversalId.toString(), created);
    }

    /** 待审批项:任务 id + 关联冲正建议 id + 创建时间。 */
    public record PendingApproval(String taskId, String reversalId, Instant createdAt) {
    }
}
