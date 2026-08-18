package com.lrj.recon.handler;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.spi.DiscrepancyHandler;
import com.lrj.recon.core.spi.HandlerContext;
import com.lrj.recon.core.spi.HandlerKind;
import com.lrj.recon.core.spi.HandlerResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 处理链 (设计 §4/§6/ADR-10): 对一条机器判差, 顺序驱动所有 {@code supports(discrepancy)} 的
 * {@link DiscrepancyHandler}, 汇总 {@link HandlerResult}。
 *
 * <p><b>事务语义 (在 matchEvaluate 的 writer 内, chunk 事务中调用)</b>:
 * <ul>
 *   <li>{@link HandlerKind#TRANSACTIONAL} (LedgerHandler / ReversalSuggestionHandler): 台账/冲正建议/审计 写库,
 *       与判差同 chunk 事务提交, 回滚则整体回滚 (幂等 upsert / insertIfAbsent 保重试等价);</li>
 *   <li>{@link HandlerKind#EXTERNAL_SIDE_EFFECT} (AlertHandler): <b>只 insert alert_outbox, 不在此发送</b>,
 *       外部投递交批后 alertRelayStep + {@code @Scheduled} 中继, 与可重试的 chunk 事务解耦。</li>
 * </ul>
 * 全部 handler 幂等 (幂等键 = handlerId+fingerprint), chunk 重试不重复生成冲正/告警/审计。纯 Java 零框架。
 */
public final class DiscrepancyHandlerChain {

    private final List<DiscrepancyHandler> handlers;

    public DiscrepancyHandlerChain(List<DiscrepancyHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    /** 处理链中的全部 handler (只读)。 */
    public List<DiscrepancyHandler> handlers() {
        return handlers;
    }

    /**
     * 驱动处理链: 对 {@code discrepancy} 依次执行所有 {@code supports} 的 handler, 返回各自结果。
     * 由 caller (matchEvaluate writer) 在 chunk 事务内调用。
     */
    public List<HandlerResult> handle(Discrepancy discrepancy, HandlerContext ctx) {
        List<HandlerResult> results = new ArrayList<>();
        for (DiscrepancyHandler h : handlers) {
            if (h.supports(discrepancy)) {
                results.add(h.handle(discrepancy, ctx));
            }
        }
        return results;
    }
}
