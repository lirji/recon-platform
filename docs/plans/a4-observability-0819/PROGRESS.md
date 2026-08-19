# A4 可观测性 + 健康检查 + 配置/密钥 · 实施进度

> 路线图 `docs/PHASE2_ROADMAP.md` Track A · A4(P1 · 工作量 M)。目标:接 actuator(liveness/readiness/metrics)
> + Micrometer→Prometheus + 批作业失败告警 + 结构化日志;外部化配置与密钥。**已完成 2026-08-19**。

## 已完成 ✅

| # | 交付 | 文件 | 说明 |
|---|---|---|---|
| 1 | 依赖 | `recon-batch/pom.xml` | `spring-boot-starter-actuator` + `micrometer-registry-prometheus`(runtime)+ `logstash-logback-encoder:7.4` |
| 2 | actuator/探针/采集 | `application.yml`(management 段) | 暴露 `health,info,metrics,prometheus`;liveness/readiness 探针;readiness 组 = `readinessState + db`;公共标签 `application` |
| 3 | 批作业失败告警(计量+日志侧) | `recon-batch/.../job/ReconJobMetricsListener.java`(新) | `recon.job.failures{job,scenario}` 计数 + `recon.job.duration{job,status}` 计时;FAILED 打结构化 ERROR |
| 4 | 挂 listener | `config/BatchConfig.java`、`config/MarketingThreeWayConfig.java` | 两个 Job(`reconciliationJob`/`marketingThreeWayJob`)`.listener(jobMetricsListener)` |
| 5 | 结构化日志 | `recon-batch/src/main/resources/logback-spring.xml`(新) | `secure` profile 输出行分隔 JSON;`!secure` 保持 Spring Boot 默认可读控制台 |
| 6 | 健康探针接线 | `compose.yml` | backend healthcheck 由 `/recon/dashboard` 改打 `/actuator/health/readiness` |
| 7 | 文档 | `README.md`「可观测性」小节、`docs/PHASE2_ROADMAP.md` A4 | 端点表 + Prometheus 告警示例 + 配置/密钥外部化说明 |
| 8 | 测试 | `web/ActuatorEndpointsTest.java`、`job/ReconJobMetricsListenerTest.java`(新) | 见下 |

## 指标口径

- **Spring Batch 自动**:MeterRegistry 存在时产 `spring_batch_job_*` / `spring_batch_step_*`(含 `status="FAILED"`)。
- **本平台自定义**(`ReconJobMetricsListener`):
  - `recon_job_failures_total{job,scenario}` —— Job FAILED 自增;
  - `recon_job_duration_seconds{job,status}` —— Job 端到端耗时。
- Prometheus 告警示例:`increase(recon_job_failures_total[15m]) > 0`。
- 「失败」同时打结构化 ERROR 日志(`recon job FAILED job=… scenario=… runId=… exitCode=… reason=…`),供日志告警键控;**真正外发通道是 A2 的 `@Primary AlertDispatcher`**,本类只保证失败在计量+日志中可见可告警。

## 安全边界(secure profile)

- `/actuator/health`、`/actuator/health/**`、`/actuator/info` 已由 A1 `CasdoorSecurityConfig` permitAll(供无凭证探针;health details `when_authorized` 不泄露)。
- `/actuator/prometheus`、`/actuator/metrics` 落 `anyRequest().authenticated()` —— 采集器需带 Bearer 或在受控内网经网关限制。**A4 未改动 A1 安全配置**。

## 验证证据(2026-08-19)

- `ActuatorEndpointsTest`(dev,MockMvc)**4/4**:`/actuator/health`=UP、liveness/readiness 探针可达、`/actuator/prometheus` 导出且含 `application="recon-batch"` 标签。
- `ReconJobMetricsListenerTest`(SimpleMeterRegistry,无上下文)**2/2**:FAILED 自增失败计数器 + 记 duration(status=FAILED);COMPLETED 只记 duration。
- `./mvnw -q -pl recon-batch -am test` 全绿(actuator/micrometer/logback/listener 接入后无回归,ArchUnit 门禁通过)。

## 诚实边界 / 待办

- **secure profile 的 JSON 日志**为 profile 门控;结构由 logstash-logback-encoder(依赖已解析)+ 标准 `<springProfile>` 保证,secure 上下文测试(`SecurityRouteMatrixTest`)启动时激活该分支无 logback 配置错误。生产切 `SPRING_PROFILES_ACTIVE=secure` 时建议 tail 首屏日志确认 JSON 一次(最终产物冒烟)。
- Grafana 仪表盘/Prometheus 抓取配置为部署侧产物,不在本仓库范围。
