package com.lrj.recon.handler;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.spi.DiscrepancyHandler;
import com.lrj.recon.core.spi.HandlerContext;
import com.lrj.recon.core.spi.HandlerKind;
import com.lrj.recon.core.spi.HandlerResult;

/**
 * 工单处理器 <b>no-op 占位</b> (设计 §4/Non-goals): 阶段二对接 workflow-platform (Flowable) 起人工工单。
 * MVP 不引入任何 Flowable 依赖 ({@code org.flowable..} 由 ArchUnit 门禁), {@link #supports} 恒为 {@code false},
 * 故永不参与处理链、不产生任何副作用。保留此类使处理链接口/装配在阶段二可无缝接入真实工单。
 */
public final class FlowableTicketHandler implements DiscrepancyHandler {

    @Override
    public String handlerId() {
        return HandlerIds.FLOWABLE_TICKET;
    }

    @Override
    public boolean supports(Discrepancy discrepancy) {
        return false; // 占位: MVP 不起工单
    }

    @Override
    public HandlerResult handle(Discrepancy discrepancy, HandlerContext ctx) {
        // supports() 恒 false, 理论不可达; 防御性返回幂等无操作。
        return HandlerResult.skippedDuplicate(HandlerIds.idempotencyKey(handlerId(),
                discrepancy == null ? "" : discrepancy.fingerprint()));
    }

    @Override
    public HandlerKind kind() {
        return HandlerKind.EXTERNAL_SIDE_EFFECT;
    }
}
