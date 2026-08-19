# A2 生产级 AlertDispatcher · 实施进度

> 路线图 `docs/PHASE2_ROADMAP.md` Track A · A2(P1 · 工作量 S)。目标:用 `@Primary` 把日志兜底替换为真实
> webhook/邮件/IM;outbox + 中继已就绪,只差可插拔 dispatcher。**已完成 2026-08-19**。

## 已完成 ✅

| # | 交付 | 文件 | 说明 |
|---|---|---|---|
| 1 | 通道配置 | `recon-batch/.../alert/AlertWebhookProperties.java`(新) | `@ConfigurationProperties("recon.alert.webhook")`:url / 连接·读超时 / 可选 header-name·header-value(签名或鉴权) |
| 2 | Webhook 投递器 | `recon-batch/.../alert/WebhookAlertDispatcher.java`(新) | 向 URL POST JSON 信封(idempotencyKey/runId/fingerprint/attempt/payload),`X-Idempotency-Key` 头供下游去重;2xx→true(SENT),非 2xx/超时/异常→false(FAILED 补投);计量 `recon.alert.dispatch{channel,outcome}` |
| 3 | 装配 | `recon-batch/.../alert/AlertDispatcherConfig.java`(新) | `@Bean @Primary @ConditionalOnExpression`(URL 非空白才启用);构建带超时的 RestClient |
| 4 | 配置 | `application.yml`(recon.alert.webhook) | env 默认全空(url 空 = 不启用,LoggingAlertDispatcher 兜底) |
| 5 | 文档 | `README.md`、`docs/PHASE2_ROADMAP.md` A2 | 告警投递小节 + 接非 HTTP 通道指引 |
| 6 | 测试 | `alert/WebhookAlertDispatcherTest.java`、`alert/AlertDispatcherWiringTest.java`(新) | 见下 |

## 关键设计

- **协议无关**:POST 通用 JSON 信封,一个 dispatcher 适配钉钉/飞书/Slack 自定义机器人及 HTTP 告警网关;接
  SMTP/厂商 SDK 只需再实现 `AlertDispatcher` + `@Primary`,无需改中继。
- **空 URL 判定**:用 `@ConditionalOnExpression("'${recon.alert.webhook.url:}'.trim().length() > 0")` 而非裸
  `@ConditionalOnProperty`——后者对"存在但空串"仍匹配,会让空 URL 误启用 webhook。
- **与中继契约对齐**(`AlertRelayService`):投递在中继短事务外执行;返回 false 或抛异常都置 FAILED + attempt,
  由 alertRelayStep / `@Scheduled` 下轮补投(at-least-once,下游按幂等键去重),`recon.alert.max-attempt` 后视死信。
- **密钥外部化**(承 A4):签名/鉴权头值经 `RECON_ALERT_WEBHOOK_HEADER_VALUE` 环境变量注入,不落配置文件/镜像。

## 验证证据(2026-08-19)

- `WebhookAlertDispatcherTest`(MockRestServiceServer,无真实网络)**2/2**:2xx→true+计量 sent、POST 带
  `X-Idempotency-Key` 与 `Authorization` 头且 body 为 JSON 信封;5xx→false+计量 failed(交补投)。
- `AlertDispatcherWiringTest`(@SpringBootTest 设 webhook.url)**1/1**:注入的 `AlertDispatcher` 为 `WebhookAlertDispatcher`(@Primary 覆盖生效)。
- 既有 `AlertRelayServiceTest`/`AlertRelayServiceExceptionSafetyTest` 全绿(中继语义无回归)。

## 诚实边界

- 只实现了 webhook(HTTP)通道;SMTP/IM SDK 留作按需扩展点(实现 `AlertDispatcher` + `@Primary`)。
- 未做投递重试退避/断路器——沿用中继的 at-least-once + max-attempt 死信语义,未额外加 dispatcher 内重试。
