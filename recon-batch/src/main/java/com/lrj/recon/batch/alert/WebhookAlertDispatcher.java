package com.lrj.recon.batch.alert;

import com.lrj.recon.core.domain.model.AlertOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A2 生产级 {@link AlertDispatcher} · 通用 Webhook 通道。仅当 {@code recon.alert.webhook.url} 非空时经
 * {@link AlertDispatcherConfig} 注册为 {@code @Primary},覆盖 {@link LoggingAlertDispatcher}。
 *
 * <p>向配置 URL POST 一个 JSON 信封(idempotencyKey/runId/fingerprint/attempt/payload),协议无关,适配
 * 钉钉/飞书/Slack 自定义机器人或 HTTP 告警网关。幂等键随 {@code X-Idempotency-Key} 头下发,供下游对 at-least-once
 * 重复投递去重(设计 §7)。2xx 视为成功(中继置 SENT);非 2xx / 连接超时 / 异常一律失败(返回 {@code false},中继置
 * FAILED + attempt,由后续补投重来 —— 契约见 {@link AlertRelayService})。每次投递计量
 * {@code recon.alert.dispatch{channel=webhook,outcome=sent|failed}}(A4 可观测性)。
 *
 * <p>外部 IO 由 {@link AlertRelayService} 在<b>中继短事务之外</b>执行,超时不持锁跨网络。
 */
public class WebhookAlertDispatcher implements AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertDispatcher.class);

    private final RestClient http;
    private final AlertWebhookProperties props;
    private final MeterRegistry meters;

    public WebhookAlertDispatcher(RestClient http, AlertWebhookProperties props, MeterRegistry meters) {
        this.http = http;
        this.props = props;
        this.meters = meters;
    }

    @Override
    public boolean dispatch(AlertOutbox entry) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("idempotencyKey", entry.idempotencyKey());
        envelope.put("runId", entry.runId());
        envelope.put("fingerprint", entry.fingerprint());
        envelope.put("attempt", entry.attempt());
        envelope.put("payload", entry.payload());

        try {
            RestClient.RequestBodySpec req = http.post()
                    .uri(props.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Idempotency-Key", entry.idempotencyKey());
            if (StringUtils.hasText(props.getHeaderName()) && StringUtils.hasText(props.getHeaderValue())) {
                req = req.header(props.getHeaderName(), props.getHeaderValue());
            }
            // 默认状态处理器对 4xx/5xx 抛异常 → 落入 catch 记 failed; 2xx 到这里即成功。
            ResponseEntity<Void> resp = req.body(envelope).retrieve().toBodilessEntity();
            boolean ok = resp.getStatusCode().is2xxSuccessful();
            record(ok);
            if (!ok) {
                log.warn("[alert] webhook 非 2xx status={} idem={}", resp.getStatusCode(), entry.idempotencyKey());
            }
            return ok;
        } catch (RuntimeException e) {
            record(false);
            log.warn("[alert] webhook 投递失败 idem={} url={}: {}", entry.idempotencyKey(), props.getUrl(), e.toString());
            return false;
        }
    }

    private void record(boolean ok) {
        meters.counter("recon.alert.dispatch", "channel", "webhook", "outcome", ok ? "sent" : "failed").increment();
    }
}
