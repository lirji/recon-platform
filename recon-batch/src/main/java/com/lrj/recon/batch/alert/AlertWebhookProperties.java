package com.lrj.recon.batch.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A2 生产级告警投递 · Webhook 通道配置。{@code recon.alert.webhook.url} 非空时,
 * {@link WebhookAlertDispatcher} 作为 {@code @Primary} {@link AlertDispatcher} 生效并覆盖
 * {@link LoggingAlertDispatcher};为空(本地/测试默认)则仍用日志兜底,不发外部请求。
 *
 * <p>通道协议无关:向配置的 URL POST 一个 JSON 信封(idempotencyKey/runId/fingerprint/payload/attempt),
 * 适配钉钉/飞书/Slack 自定义机器人或通用 HTTP 告警网关。签名/鉴权经可选的 {@code header-name}/{@code header-value}
 * 注入请求头(密钥经环境变量注入,不落配置文件)。
 */
@ConfigurationProperties(prefix = "recon.alert.webhook")
public class AlertWebhookProperties {

    /** Webhook 目标 URL;空 = 不启用 webhook 通道(用 LoggingAlertDispatcher 兜底)。 */
    private String url;

    /** 连接超时(毫秒)。外部 IO 在中继短事务外执行,超时使本条投递失败并由后续补投重来。 */
    private int connectTimeoutMs = 3000;

    /** 读取超时(毫秒)。 */
    private int readTimeoutMs = 5000;

    /** 可选鉴权/签名头名(如 {@code Authorization} / {@code X-Signature});空则不加。 */
    private String headerName;

    /** 可选鉴权/签名头值(如 {@code Bearer xxx});经环境变量注入,不落配置文件。 */
    private String headerValue;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getHeaderValue() {
        return headerValue;
    }

    public void setHeaderValue(String headerValue) {
        this.headerValue = headerValue;
    }
}
