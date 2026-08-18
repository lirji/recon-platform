package com.lrj.recon.handler;

import com.lrj.recon.core.application.port.out.AlertOutboxRepository;
import com.lrj.recon.core.domain.model.AlertOutbox;
import com.lrj.recon.core.domain.model.AlertStatus;
import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.spi.DiscrepancyHandler;
import com.lrj.recon.core.spi.HandlerContext;
import com.lrj.recon.core.spi.HandlerKind;
import com.lrj.recon.core.spi.HandlerResult;

import java.time.Instant;
import java.util.UUID;

/**
 * 告警处理器 (设计 §4/ADR-10, EXTERNAL_SIDE_EFFECT): <b>只写 {@code alert_outbox} (status=PENDING), 绝不在
 * 可重试的 chunk 事务内直接发送外部告警</b>。真正投递由批后 alertRelayStep + {@code @Scheduled} 中继完成
 * (at-least-once, 幂等键去重), 使外部副作用与 chunk 回滚/重试彻底解耦, chunk 重试不重复发。
 *
 * <p>入队本身是一次 DB 写, 与判差同 chunk 事务提交/回滚 (回滚则该告警也不入队, 无副作用泄漏); 幂等键 =
 * alert + fingerprint, 命中 {@code uk_outbox} 则不重复入队。受理所有差异类型 (MVP 全量告警)。
 */
public final class AlertHandler implements DiscrepancyHandler {

    private final AlertOutboxRepository outbox;

    public AlertHandler(AlertOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public String handlerId() {
        return HandlerIds.ALERT;
    }

    @Override
    public boolean supports(Discrepancy discrepancy) {
        return discrepancy != null;
    }

    @Override
    public HandlerResult handle(Discrepancy d, HandlerContext ctx) {
        String idem = HandlerIds.idempotencyKey(handlerId(), d.fingerprint());
        String runId = ctx == null ? d.runId() : ctx.runId();
        boolean inserted = outbox.insertIfAbsent(AlertOutbox.builder()
                .id(UUID.randomUUID().toString())
                .runId(runId)
                .fingerprint(d.fingerprint())
                .payload(payloadOf(d))
                .status(AlertStatus.PENDING)      // 只入队, 不发送
                .attempt(0)
                .idempotencyKey(idem)
                .createdAt(Instant.now())
                .build());
        return inserted ? HandlerResult.applied(idem) : HandlerResult.skippedDuplicate(idem);
    }

    /** 极简 JSON 载荷 (值均为受控枚举/数字, 无需转义); 阶段二可换结构化告警模板。 */
    private static String payloadOf(Discrepancy d) {
        return "{\"segmentId\":\"" + d.segmentId() + "\",\"type\":\"" + d.type()
                + "\",\"fingerprint\":\"" + d.fingerprint()
                + "\",\"deltaAmountMinor\":" + d.deltaAmountMinor() + "}";
    }

    @Override
    public HandlerKind kind() {
        return HandlerKind.EXTERNAL_SIDE_EFFECT;
    }
}
