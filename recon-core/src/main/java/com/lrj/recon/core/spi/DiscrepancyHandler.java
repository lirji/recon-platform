package com.lrj.recon.core.spi;

import com.lrj.recon.core.domain.model.Discrepancy;

/**
 * 插件 4) 处理: 告警 / 台账 / 冲正建议。幂等键 = fingerprint + handlerId。
 *
 * <p>MVP (M0) 只定义接口; Ledger/ReversalSuggestion/Alert 实现归 M5。
 */
public interface DiscrepancyHandler {

    String handlerId();

    boolean supports(Discrepancy discrepancy);

    HandlerResult handle(Discrepancy discrepancy, HandlerContext ctx);

    /** 事务性 or 外部副作用 (决定是否进 chunk 事务)。 */
    HandlerKind kind();
}
