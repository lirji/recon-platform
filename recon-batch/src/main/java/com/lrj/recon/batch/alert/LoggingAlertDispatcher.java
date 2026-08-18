package com.lrj.recon.batch.alert;

import com.lrj.recon.core.domain.model.AlertOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link AlertDispatcher} 的 MVP 默认实现: 记日志即视为投递成功。生产用真实告警通道实现覆盖
 * (声明 @Primary 的 Bean 即可; 本实现仅在无自定义 dispatcher 时兜底)。
 */
@Component
public class LoggingAlertDispatcher implements AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingAlertDispatcher.class);

    @Override
    public boolean dispatch(AlertOutbox entry) {
        log.info("[alert] dispatch idem={} run={} fp={} payload={}",
                entry.idempotencyKey(), entry.runId(), entry.fingerprint(), entry.payload());
        return true;
    }
}
