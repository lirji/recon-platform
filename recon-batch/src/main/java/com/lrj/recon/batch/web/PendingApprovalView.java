package com.lrj.recon.batch.web;

import java.time.Instant;

/**
 * B5 审批待办富化视图:在 Flowable 待办 (taskId/reversalId/createdAt) 之上,由 controller 层 join
 * {@code reversal_suggestion} 补金额/币种/状态/血缘,供前端审批页直显「批的是哪笔钱」。
 *
 * <p>金额 {@code suggestedAmountMinor} 为 minor 十进制字符串(禁前端转 number 做业务计算);
 * join miss(建议不存在)时业务字段为 null,前端显「—」。
 */
public record PendingApprovalView(
        String taskId,
        String reversalId,
        Instant createdAt,
        String suggestedAmountMinor,
        String currency,
        String status,
        String groupKey,
        String runId) {
}
