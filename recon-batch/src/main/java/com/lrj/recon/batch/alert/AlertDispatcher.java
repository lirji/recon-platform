package com.lrj.recon.batch.alert;

import com.lrj.recon.core.domain.model.AlertOutbox;

/**
 * 告警投递器 (设计 §6 Step4 / ADR-10): 真正把一条 {@code alert_outbox} 投递给外部告警通道 (钉钉/邮件/PagerDuty 等)。
 *
 * <p>由 {@link AlertRelayService} 在<b>批后中继</b> (alertRelayStep + {@code @Scheduled}) 调用, <b>绝不</b>在可重试的
 * chunk 事务内调用 (外部副作用与事务解耦)。at-least-once + 幂等键 ({@link AlertOutbox#idempotencyKey()}) 保重复投递
 * 在下游可去重。MVP 提供 {@link LoggingAlertDispatcher} 记日志占位; 生产替换为真实通道实现 (可 @Primary 覆盖)。
 *
 * @return 投递成功 {@code true} (中继置 SENT); 失败 {@code false} 或抛异常 (中继置 FAILED + attempt, 待补投)。
 */
public interface AlertDispatcher {

    boolean dispatch(AlertOutbox entry);
}
