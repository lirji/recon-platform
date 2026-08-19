package com.lrj.recon.batch.alert;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A2 告警投递器装配。默认(无 {@code recon.alert.webhook.url})只有 {@link LoggingAlertDispatcher} 兜底;
 * 配了 webhook URL 则注册 {@link WebhookAlertDispatcher} 为 {@code @Primary},{@link AlertRelayService} 自动改用它。
 *
 * <p>用 {@link ConditionalOnExpression} 判 URL <b>非空白</b>(而非裸 {@code @ConditionalOnProperty} —— 后者对
 * "存在但为空串"仍匹配,会让空 URL 误启用 webhook)。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlertWebhookProperties.class)
public class AlertDispatcherConfig {

    @Bean
    @Primary
    @ConditionalOnExpression("'${recon.alert.webhook.url:}'.trim().length() > 0")
    public AlertDispatcher webhookAlertDispatcher(RestClient.Builder builder,
                                                  AlertWebhookProperties props,
                                                  MeterRegistry meters) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.getConnectTimeoutMs());
        requestFactory.setReadTimeout(props.getReadTimeoutMs());
        RestClient http = builder.requestFactory(requestFactory).build();
        return new WebhookAlertDispatcher(http, props, meters);
    }
}
