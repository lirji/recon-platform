package com.lrj.recon.handler;

import com.lrj.recon.core.application.port.out.DiscrepancyActionRepository;
import com.lrj.recon.core.application.port.out.ReversalSuggestionRepository;
import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyAction;
import com.lrj.recon.core.domain.model.DiscrepancyActionType;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.ReversalStatus;
import com.lrj.recon.core.domain.model.ReversalSuggestion;
import com.lrj.recon.core.spi.DiscrepancyHandler;
import com.lrj.recon.core.spi.HandlerContext;
import com.lrj.recon.core.spi.HandlerKind;
import com.lrj.recon.core.spi.HandlerResult;

import java.time.Instant;
import java.util.UUID;

/**
 * 冲正建议处理器 (设计 §4/ADR-7, TRANSACTIONAL): 对<b>金额型差异</b> (AMOUNT_MISMATCH / GROUP_SUM_MISMATCH,
 * 币种明确、delta≠0) 在同 chunk 事务内 {@code insertIfAbsent} 生成一条 {@link ReversalSuggestion}
 * (幂等键 = reversal-suggestion + fingerprint) + 一条 {@code discrepancy_action(REVERSAL_SUGGESTION)} 审计。
 *
 * <p><b>资金红线</b>: 只生成建议 (status={@code SUGGESTED}), <b>无任何资金动作</b>, 不自动执行冲正 (阶段二人工确认)。
 * 建议金额 = {@code delta = expected - actual} (signed 最小货币单位/分, 禁 double), 表示"把实际额纠回应对额"的方向与量。
 * 幂等: 同一 fingerprint 重复触发命中 {@code uk_rev} → 不重复生成。
 */
public final class ReversalSuggestionHandler implements DiscrepancyHandler {

    private final ReversalSuggestionRepository reversals;
    private final DiscrepancyActionRepository actions;

    public ReversalSuggestionHandler(ReversalSuggestionRepository reversals, DiscrepancyActionRepository actions) {
        this.reversals = reversals;
        this.actions = actions;
    }

    @Override
    public String handlerId() {
        return HandlerIds.REVERSAL_SUGGESTION;
    }

    @Override
    public boolean supports(Discrepancy d) {
        if (d == null || d.type() == null) {
            return false;
        }
        // 金额型差异且币种明确、delta≠0 才建议冲正; MISSING/EXTRA/BRIDGE_BROKEN/CURRENCY_MISMATCH 非金额纠偏场景不建议。
        boolean amountKind = d.type() == DiscrepancyType.AMOUNT_MISMATCH
                || d.type() == DiscrepancyType.GROUP_SUM_MISMATCH;
        return amountKind && d.currency() != null && d.deltaAmountMinor() != 0L;
    }

    @Override
    public HandlerResult handle(Discrepancy d, HandlerContext ctx) {
        String idem = HandlerIds.idempotencyKey(handlerId(), d.fingerprint());
        String operator = ctx == null || ctx.operator() == null ? "system" : ctx.operator();
        String runId = ctx == null ? d.runId() : ctx.runId();

        boolean inserted = reversals.insertIfAbsent(ReversalSuggestion.builder()
                .id(UUID.randomUUID().toString())
                .fingerprint(d.fingerprint())
                .runId(runId)
                .groupKey(d.groupKey())
                .suggestedAmountMinor(d.deltaAmountMinor()) // signed 分, 无资金动作
                .currency(d.currency())
                .status(ReversalStatus.SUGGESTED)
                .idempotencyKey(idem)
                .operator(operator)
                .createdAt(Instant.now())
                .build());

        // 审计: 与冲正建议同幂等口径 (即便建议已存在, 审计也幂等命中不重复)。
        actions.insertIfAbsent(DiscrepancyAction.builder()
                .id(UUID.randomUUID().toString())
                .fingerprint(d.fingerprint())
                .actionType(DiscrepancyActionType.REVERSAL_SUGGESTION)
                .idempotencyKey(idem)
                .payload("suggestedAmountMinor=" + d.deltaAmountMinor() + "," + d.currency())
                .operator(operator)
                .createdAt(Instant.now())
                .build());

        return inserted ? HandlerResult.applied(idem) : HandlerResult.skippedDuplicate(idem);
    }

    @Override
    public HandlerKind kind() {
        return HandlerKind.TRANSACTIONAL;
    }
}
