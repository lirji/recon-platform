package com.lrj.recon.batch.service;

import com.lrj.recon.core.domain.model.ReversalSuggestion;
import com.lrj.recon.core.spi.ReversalExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * B3 · 默认冲正执行器:<b>不动真钱</b>,仅结构化日志(与 {@code LoggingAlertDispatcher} 同范式)。
 * 生产以 {@code @Primary ReversalExecutor} 注入接真实清结算/支付网关的适配器覆盖之。
 */
@Component
public class LoggingReversalExecutor implements ReversalExecutor {

    private static final Logger log = LoggerFactory.getLogger(LoggingReversalExecutor.class);

    @Override
    public String executorId() {
        return "logging";
    }

    @Override
    public String execute(ReversalSuggestion r) {
        log.info("[reversal] SIMULATED execution (no real fund action) id={} amountMinor={} {} idem={}",
                r.id(), r.suggestedAmountMinor(), r.currency(), r.idempotencyKey());
        return "SIMULATED:" + r.idempotencyKey();
    }
}
