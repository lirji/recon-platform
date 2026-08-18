package com.lrj.recon.handler;

import com.lrj.recon.core.application.port.out.DiscrepancyActionRepository;
import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyAction;
import com.lrj.recon.core.domain.model.DiscrepancyActionType;
import com.lrj.recon.core.spi.DiscrepancyHandler;
import com.lrj.recon.core.spi.HandlerContext;
import com.lrj.recon.core.spi.HandlerKind;
import com.lrj.recon.core.spi.HandlerResult;

import java.time.Instant;
import java.util.UUID;

/**
 * 台账处理器 (设计 §4, TRANSACTIONAL): 差异台账 {@code discrepancy} 已由 matchEvaluate 的 writer 幂等 upsert,
 * <b>本 handler 不重复写台账</b>, 只在同 chunk 事务内落一条 {@code discrepancy_action(LEDGER)} 审计
 * (幂等键 = ledger + fingerprint), 标记该差异已被处理链受理 —— 为后续动作 (冲正建议 / 告警 / 人工核销) 提供审计锚。
 *
 * <p>受理所有差异类型。幂等: 同一 fingerprint 重复触发 (chunk 重试 / 重跑) 命中 {@code uk_action} → 不重复留痕。
 */
public final class LedgerHandler implements DiscrepancyHandler {

    private final DiscrepancyActionRepository actions;

    public LedgerHandler(DiscrepancyActionRepository actions) {
        this.actions = actions;
    }

    @Override
    public String handlerId() {
        return HandlerIds.LEDGER;
    }

    @Override
    public boolean supports(Discrepancy discrepancy) {
        return discrepancy != null;
    }

    @Override
    public HandlerResult handle(Discrepancy d, HandlerContext ctx) {
        String idem = HandlerIds.idempotencyKey(handlerId(), d.fingerprint());
        String operator = ctx == null || ctx.operator() == null ? "system" : ctx.operator();
        boolean inserted = actions.insertIfAbsent(DiscrepancyAction.builder()
                .id(UUID.randomUUID().toString())
                .fingerprint(d.fingerprint())
                .actionType(DiscrepancyActionType.LEDGER)
                .idempotencyKey(idem)
                .payload("type=" + d.type() + ",delta=" + d.deltaAmountMinor())
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
