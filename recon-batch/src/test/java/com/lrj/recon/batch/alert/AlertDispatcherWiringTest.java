package com.lrj.recon.batch.alert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2 · 装配契约:配了 {@code recon.alert.webhook.url} 时,{@link WebhookAlertDispatcher} 以 {@code @Primary}
 * 覆盖 {@link LoggingAlertDispatcher} 被注入。默认(无 URL)用日志兜底,由其余全绿的 @SpringBootTest 隐式覆盖。
 */
@SpringBootTest(properties = "recon.alert.webhook.url=https://hooks.example.test/recon-alert")
class AlertDispatcherWiringTest {

    @Autowired
    AlertDispatcher dispatcher;

    @Test
    void webhookDispatcherIsPrimaryWhenUrlConfigured() {
        assertThat(dispatcher).isInstanceOf(WebhookAlertDispatcher.class);
    }
}
