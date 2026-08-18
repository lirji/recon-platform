package com.lrj.recon.batch.alert;

import com.lrj.recon.core.application.port.out.AlertOutboxRepository;
import com.lrj.recon.core.domain.model.AlertOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 告警中继 (设计 §6 Step4 / §7 / ADR-10): 读 {@code alert_outbox} 可中继条目 (PENDING 首投 + FAILED 补投),
 * 经 {@link AlertDispatcher} at-least-once 投递, 成功置 SENT、失败置 FAILED + attempt。
 *
 * <p><b>脱离 chunk 事务</b>: 由 alertRelayStep (批后) 与 {@link com.lrj.recon.batch.job.ReconScheduler} 的
 * {@code @Scheduled} 调用, 绝不在可重试的判差 chunk 事务内发送。<b>每条一短事务</b> (REQUIRES_NEW):
 * 单条投递+状态更新独立提交, 一条失败不影响其它、不回滚账 (回滚只丢本条投递, 由后续补投重来)。投递本身在事务外执行
 * (只用短事务包状态更新), 避免长事务持锁跨网络 IO。
 */
@Service
public class AlertRelayService {

    private static final Logger log = LoggerFactory.getLogger(AlertRelayService.class);

    private final AlertOutboxRepository outbox;
    private final AlertDispatcher dispatcher;
    private final TransactionTemplate txTemplate;
    private final int maxAttempt;

    public AlertRelayService(AlertOutboxRepository outbox,
                             AlertDispatcher dispatcher,
                             PlatformTransactionManager txManager,
                             @Value("${recon.alert.max-attempt:5}") int maxAttempt) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxAttempt = maxAttempt;
    }

    /**
     * 中继一轮: 取所有可投递条目, 逐条投递并更新状态。返回本轮成功投递 (置 SENT) 的条数。
     * 单条异常被吞并计为失败 (FAILED + attempt), 不中断本轮其它条目。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int relayOnce() {
        List<AlertOutbox> batch = outbox.listRetryable(maxAttempt);
        int sent = 0;
        for (AlertOutbox entry : batch) {
            if (relayEntry(entry)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean relayEntry(AlertOutbox entry) {
        boolean ok;
        try {
            ok = dispatcher.dispatch(entry); // 外部投递在短事务外执行
        } catch (RuntimeException dispatchFailure) {
            log.warn("[alert] dispatch threw for idem={}, marking FAILED", entry.idempotencyKey(), dispatchFailure);
            ok = false;
        }
        boolean success = ok;
        txTemplate.executeWithoutResult(status -> {
            if (success) {
                outbox.markSent(entry.id(), Instant.now());
            } else {
                outbox.markFailed(entry.id());
            }
        });
        return success;
    }
}
