package com.lrj.recon.workflow.flowable;

import com.lrj.recon.core.domain.model.ReversalStatus;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;

/**
 * 冲正审批 BPMN 结束事件监听器:到达 {@code confirmedEnd}→CONFIRMED / {@code discardedEnd}→DISCARDED 时,
 * 读流程变量 reversalId/operator,调 {@link ReversalDecisionSink} 落地状态。作为 Flowable bean 以
 * {@code delegateExpression="${reversalStatusListener}"} 注册进引擎。
 */
public final class ReversalStatusListener implements ExecutionListener {

    static final String BEAN_NAME = "reversalStatusListener";
    private static final String CONFIRMED_END = "confirmedEnd";

    private final transient ReversalDecisionSink sink;

    public ReversalStatusListener(ReversalDecisionSink sink) {
        this.sink = sink;
    }

    @Override
    public void notify(DelegateExecution execution) {
        ReversalStatus status = CONFIRMED_END.equals(execution.getCurrentActivityId())
                ? ReversalStatus.CONFIRMED
                : ReversalStatus.DISCARDED;
        String reversalId = (String) execution.getVariable("reversalId");
        String operator = (String) execution.getVariable("operator");
        String note = (String) execution.getVariable("note");
        if (note != null && note.isBlank()) {
            note = null; // 空审批意见视为无留痕, 落地层 COALESCE 不覆盖已有 decision_note
        }
        sink.onDecision(reversalId, status, operator, note);
    }
}
