package com.lrj.recon.workflow.flowable;

import com.lrj.recon.core.domain.model.ReversalStatus;

/**
 * 冲正审批终局回调:BPMN 到达 confirmedEnd/discardedEnd 时,{@link ReversalStatusListener} 调此 sink 落地状态。
 * 组合根提供的实现更新 {@code reversal_suggestion.status};测试可用捕获实现。纯回调,零 Flowable 泄漏到调用方。
 */
@FunctionalInterface
public interface ReversalDecisionSink {

    /**
     * @param note 审批意见 (B5 必填); 空串视为无留痕, 落地层按 null 处理 (COALESCE 不覆盖已有)。
     */
    void onDecision(String reversalId, ReversalStatus status, String operator, String note);
}
