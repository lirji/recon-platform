package com.lrj.recon.batch.service;

import com.lrj.recon.core.application.port.out.DiscrepancyActionRepository;
import com.lrj.recon.core.application.port.out.ReversalSuggestionRepository;
import com.lrj.recon.core.domain.model.DiscrepancyAction;
import com.lrj.recon.core.domain.model.DiscrepancyActionType;
import com.lrj.recon.core.domain.model.ReversalStatus;
import com.lrj.recon.core.domain.model.ReversalSuggestion;
import com.lrj.recon.core.spi.ReversalExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * B3 · 冲正执行编排。只对<b>已审批通过(CONFIRMED)</b>的冲正调 {@link ReversalExecutor} 执行真实资金动作,
 * 成功置 {@code EXECUTED}、失败置 {@code EXECUTION_FAILED}(不吞异常)。
 *
 * <p><b>安全护栏</b>:①非 CONFIRMED 拒绝执行(fail-fast);②已 EXECUTED 幂等跳过(不重复动钱);
 * ③审批(B5)与执行(本类)是<b>两个独立控制点</b>,执行须显式触发(不随审批自动动钱);④审计留痕
 * {@code discrepancy_action(REVERSAL_EXECUTED)}。资金红线:真实动钱只发生在这里的 {@code executor.execute}。
 */
@Service
public class ReversalExecutionService {

    private final ReversalSuggestionRepository reversals;
    private final DiscrepancyActionRepository actions;
    private final ReversalExecutor executor;

    public ReversalExecutionService(ReversalSuggestionRepository reversals,
                                    DiscrepancyActionRepository actions,
                                    ReversalExecutor executor) {
        this.reversals = reversals;
        this.actions = actions;
        this.executor = executor;
    }

    public Result execute(String reversalId, String operator) {
        ReversalSuggestion r = reversals.find(reversalId)
                .orElseThrow(() -> new NotFoundException("reversal not found: " + reversalId));
        if (r.status() == ReversalStatus.EXECUTED) {
            return new Result(reversalId, ReversalStatus.EXECUTED, true, "already executed (idempotent)");
        }
        if (r.status() != ReversalStatus.CONFIRMED) {
            throw new IllegalStateException("reversal " + reversalId + " is " + r.status()
                    + "; only CONFIRMED reversals can be executed");
        }
        String op = operator == null || operator.isBlank() ? "system" : operator;
        try {
            String ref = executor.execute(r);
            reversals.updateStatus(reversalId, ReversalStatus.EXECUTED, op);
            audit(r, op, "reversal-exec-ok:" + r.idempotencyKey(), "executed ref=" + ref);
            return new Result(reversalId, ReversalStatus.EXECUTED, true, ref);
        } catch (RuntimeException e) {
            reversals.updateStatus(reversalId, ReversalStatus.EXECUTION_FAILED, op);
            audit(r, op, "reversal-exec-fail:" + r.idempotencyKey(), "execution failed: " + e.getMessage());
            throw new IllegalStateException("reversal execution failed for " + reversalId + ": " + e.getMessage(), e);
        }
    }

    private void audit(ReversalSuggestion r, String operator, String idempotencyKey, String payload) {
        actions.insertIfAbsent(DiscrepancyAction.builder()
                .id(UUID.randomUUID().toString())
                .fingerprint(r.fingerprint())
                .actionType(DiscrepancyActionType.REVERSAL_EXECUTED)
                .idempotencyKey(idempotencyKey)
                .payload(payload)
                .operator(operator)
                .createdAt(Instant.now())
                .build());
    }

    /** 执行结果(纯数据):冲正 id + 终态 + 是否已执行 + 外部凭证/说明。 */
    public record Result(String reversalId, ReversalStatus status, boolean executed, String reference) {
    }
}
