# 阶段二路线图 · 两条并行 Track

> 通用自动对账系统 · 阶段二规划。把阶段一 MVP(M0–M6 + 前端 Console)之外「留位不做」的能力,拆成两条独立 Track——**Track A 生产上线加固**(决定「能不能真上线」)与 **Track B 平台化功能**(决定「通不通用」)——各自排优先级、标注现状与依赖。
>
> 口径来源:`docs/design/RECON_MVP_DESIGN.md` §1 Non-goals · §13 取舍 · §14 口径决议;`docs/KNOWN_ISSUES.md`。
> 可视化版本:见本仓库交付记录中的 Artifact 链接(私有,可分享)。
> 最后更新:2026-08-18。

## 优先级与工作量图例

| 记号 | 含义 |
|---|---|
| **P0** | 阻断上线 |
| **P1** | 上线必备 |
| **P2** | 平台核心 |
| **P3** | 按需 |
| **P4** | 远期 |
| 工作量 | S · M · L · XL |

---

## Track A · 生产上线加固

> 上线硬门槛,与功能多少无关。原则上优先于 Track B 的高价值项,因为 A1 是它们的前置。

### A1 · 认证与鉴权 — P0(阻断)· 工作量 L

- **现状**:后端零 Spring Security;`operator` 靠前端 `sessionStorage` 手填,完全不可信。
- **要做**:后端加 Spring Security + 登录;`operator` 改从**可信身份上下文**获取,不再信任请求体;角色分离 viewer / operator / admin;前端登录页替换手填操作人。
- **依赖**:无前置 —— **它本身是 B3 / B5 的硬前置**;触及全部写接口(launch / rerun / resolve / close)与 DTO 的 operator 字段。

### A2 · 生产级 AlertDispatcher — P1 · 工作量 S — ✅ 已完成(2026-08-19)

- **原现状**:只有 `LoggingAlertDispatcher`(仅打日志),告警到不了外部。
- **已交付**:`WebhookAlertDispatcher`(通用 HTTP webhook,协议无关 JSON 信封,适配钉钉/飞书/Slack/网关)。配 `RECON_ALERT_WEBHOOK_URL` 后经 `AlertDispatcherConfig` 以 `@Primary` 覆盖日志兜底(`@ConditionalOnExpression` 判 URL 非空白,空则不启用);幂等键随 `X-Idempotency-Key` 头下发,可选签名/鉴权头经 env 注入;2xx→SENT / 非 2xx·超时→FAILED 由中继补投;计量 `recon_alert_dispatch_total{channel,outcome}`。接非 HTTP 通道只需再实现 `AlertDispatcher` + `@Primary`。
- **落地**:`recon-batch/.../alert/{WebhookAlertDispatcher,AlertWebhookProperties,AlertDispatcherConfig}.java`、`application.yml`(recon.alert.webhook)、测试 `WebhookAlertDispatcherTest`/`AlertDispatcherWiringTest`、`README.md`。
- **依赖**:密钥/配置外部化(A4,已就绪);低耦合,独立交付。

### A3 · 真库端到端 + 生产 DB profile — P1 · 工作量 M — ✅ 已完成(2026-08-19)

- **原现状**:`compose.yml` 后端跑 H2 file,默认测试 H2,真库仅 Testcontainers(KI-4)。
- **已交付**:
  - 新增 **`RealDbEndToEndIT`**(系统属性驱动,直连外部 MySQL8/PG),绕开 Testcontainers 与新 Docker Engine(API≥1.55)的版本不兼容;对真实 **MySQL 8.0.46 + PG 16.15 全绿**,覆盖 V1+V2+V3 生产同款迁移、**方言 batch 序列**、collation 序/PAD SPACE、`idx_merge`、**`fetchSize=Integer.MIN_VALUE` 真流式游标**。
  - 修 `CollationRealDbIT` 潜在方言 `ANALYZE` 语法坑(MySQL 需 `ANALYZE TABLE`)——此前从未真跑故未暴露。
  - **PG 驱动 + Flyway PG 模块提为 runtime**,令「MySQL8/PG 通用」在生产成立(不再仅测试域)。
  - **`compose.mysql.yml` 叠加层**:后端跑真 MySQL 8 的端到端本地部署;README 补「生产 DB」小节(env 切换 + 真库验证命令)。
- **落地**:`recon-batch/.../RealDbEndToEndIT.java`、`compose.mysql.yml`、`recon-batch/pom.xml`、`README.md`、`KNOWN_ISSUES.md` KI-4。
- **依赖**:需真实 MySQL/PG 环境或 CI Docker(已满足)。

### A4 · 可观测性 + 健康检查 + 配置/密钥 — P1 · 工作量 M — ✅ 已完成(2026-08-19)

- **原现状**:无 actuator / micrometer / prometheus;compose healthcheck 靠打 `/recon/dashboard` 兜底。
- **已交付**:
  - **actuator + Micrometer→Prometheus**:暴露 `health,info,metrics,prometheus`;`health` 含 **liveness/readiness 探针**,readiness 组 = `readinessState + db`。
  - **批作业失败告警(计量+结构化日志侧)**:`ReconJobMetricsListener` 挂在 `reconciliationJob`/`marketingThreeWayJob` 上,产 `recon_job_failures_total{job,scenario}` + `recon_job_duration_*{job,status}`,FAILED 时打结构化 ERROR;补 Spring Batch 自动 `spring_batch_job_*`。真正外发通道仍是 A2 dispatcher。
  - **结构化日志**:`logback-spring.xml` 按 profile 分流(secure=JSON / 其余=可读控制台)。
  - **compose healthcheck** 改打 `/actuator/health/readiness`;**配置/密钥** 全环境变量外部化(README「可观测性」小节)。
- **落地**:`recon-batch/pom.xml`(actuator/micrometer/logstash-encoder)、`application.yml`(management)、`job/ReconJobMetricsListener.java`、`config/{BatchConfig,MarketingThreeWayConfig}.java`(挂 listener)、`resources/logback-spring.xml`、`compose.yml`、测试 `ActuatorEndpointsTest`/`ReconJobMetricsListenerTest`、`README.md`。
- **诚实边界**:secure profile 下 `/actuator/prometheus`、`/actuator/metrics` 需认证(采集器带 Bearer 或受控内网限制);JSON 日志分流为 profile 门控,dev/测试不受影响。
- **依赖**:无强前置;其密钥外部化能力被 A2 复用。

### A5 · KI 已知问题加固 — P2(默认关/低危)· 工作量 S–M — ✅ 已完成(2026-08-19)

- **原现状**:均为默认关 / 低危场景,已在 `KNOWN_ISSUES.md` 记录。
- **已交付**:
  - **KI-1**:`SkewConfigGuardListener`(挂两个 Job)—— skew 形状指纹 `enabled|fanout` 入 Job 级 ExecutionContext,restart 时对 **fanout 数值变** 或 **累计翻转≥2** fail-fast(单次整桶↔sub 翻转仍放行,不误伤已缓解路径);默认关时恒 no-op。测试 `ReconJobSkewFanoutRestartGuardTest` + `ReconJobShapeFlipRestartTest` 共同界定边界。
  - **KI-6**:只读诊断 `GET /recon/runs/{id}/refine-violations` —— DB 侧 `GROUP BY match_key HAVING COUNT(DISTINCT group_key)>1` 扫 staged `recon_record`,把假 BRIDGE_BROKEN/EXTRA 从「守恒抓不到」升级为「显式可发现」;不占热路径,有界+truncated 标记。测试 `RefineViolationsTest`。
- **落地**:`job/SkewConfigGuardListener.java`、`config/{BatchConfig,MarketingThreeWayConfig}`(挂 listener)、`service/ReconConsoleQueryService.refineViolations`、`service/ReconConsoleQueryRepository`+`persistence/JdbcReconConsoleQueryStore`(findRefineViolations)、`web/ReconConsoleController`、`KNOWN_ISSUES.md` KI-1/KI-6。
- **诚实边界**:KI-1 守卫是把静默错算升级为 fail-fast,运维「restart 前不改 skew 配置」约束仍在;KI-6 是事后诊断(staging 扫描),非 join 实时拦截。
- **依赖**:无 —— 不阻断上线。

---

## Track B · 平台化功能

> 阶段二 Non-goals,字段/接口已留位。把「营销三方专用」推向「通用对账平台」,按价值/依赖排。

### B1 · 三方合并只读视图 — P1(速赢)· 工作量 S–M — ✅ 已完成(后端 + 前端,2026-08-19)

- **原现状**:MVP 只出两段独立报表。
- **已交付(后端只读 API)**:`GET /recon/runs/{id}/three-way` → `ThreeWayReport`。由一个 Run 的两段报表(SEG1 营销↔账务、SEG2 账务↔渠道)**派生**单一三方 roll-up,纯读、无新 SQL、无算法风险。
  - **口径**(设计 A2 只把合并归阶段二未定细则,此处为阶段二取定并显式标注):`threeWayConsistent` = 每币种两段均在且均 balanced(**布尔与,不跨段求和金额**——spine 账务侧被两段共享,相加会重复计;故只合成状态,原始各段金额并列供下钻);`bridgeBrokenMinor` = 两段桥断额之和(两个独立断点阶段);`threeWayBalanced` = 所有币种皆 consistent。
  - **落地**:`service/ReconConsoleQueryService.threeWayRollup`、`service/ReconConsoleQueryRepository`(ThreeWayReport/CurrencyRollup DTO)、`web/ReconConsoleController`、测试 `web/ThreeWayRollupTest`。
- **已交付(前端页)**:`RunDetailDrawer` 内 Tabs「守恒报表/三方合并」(懒加载,仅 `MARKETING_3WAY` 显示);三态一致性 banner + 每币种 Card(两段 Row/Col 并列、桥断额)+ 按段 `BRIDGE_BROKEN` 下钻链接(`DiscrepanciesPage` 接 `useSearchParams` 播种)。金额全程字符串/BigInt。计划见 `docs/plans/b1-three-way-frontend-0819-1109/`。测试:Vitest 16/16 + Playwright 双 project 冒烟。
- **依赖**:无(纯读)。

### B2 · Drools 规则引擎 — P1 · 工作量 M–L — ✅ 已完成(2026-08-19)

- **原现状**:`DroolsEvaluator` 仅接口,`EvaluatorFactory` 遇 DROOLS fail-fast。
- **已交付**:
  - **新外圈模块 `recon-rules-drools`**(依赖仅 recon-core;`org.kie/org.drools` 封装其内,组合根 ArchUnit 仍禁直接依赖)。
  - **`DroolsDiscrepancyEvaluator`(策略层)**:先跑 core `DiscrepancyClassifier` 得候选(保留 fingerprint/bridge/守恒等安全关键构造),再由 DRL 规则对候选 **suppress/改判**;改判时按新 type 重算 fingerprint。**默认规则集 `discrepancy-default.drl` ≡ Exact+enable+tolerance**,parity 测试锁定。
  - **fail-fast**:DRL 编译失败→构造/启动抛异常;运行期规则异常上抛;不做静默回退。
  - **组合根装配**:`EvaluatorResolver`(EXACT/TOLERANCE→core;DROOLS→注入 bean,未启用则 fail-fast)+ `RulesConfig`(`recon.rules.drools.enabled` 门控,`extra-classpath` 叠加 ops 自定义 DRL);BatchConfig/MarketingThreeWayConfig 统一经 resolver。
  - **配置化能力**:段配 `evaluator-type=DROOLS` + 追加 DRL 即改判差行为,**不改 Java**(通向 B4)。
- **落地**:`recon-rules-drools/`(evaluator/fact/DRL/ArchUnit + parity/policy/fail-fast 测试)、`recon-batch/.../config/{EvaluatorResolver,RulesConfig}.java` + 两处 config 接线、`application.yml`(recon.rules.drools)。
- **验证**:全量 `./mvnw test` 8 模块绿(recon-rules-drools 10 + recon-batch 111,含 `DroolsWiringTest` 启动期编译验证)。
- **依赖**:接口已留,无硬前置;是 B4 配置驱动的组成部分。

### B4 · 配置驱动场景 / DSL 规则平台 — P2(核心卖点,工作量最大)· 工作量 XL — ✅ 已完成(后端平台 + 管理 UI,2026-08-19)

- **原现状**:场景硬编码装配(`recon-scenario`)。
- **分 4 期**(设计与进度见 `docs/plans/b4-config-driven-0819/DESIGN.md`):
  - **Phase 1 ✅ 声明式模型 + 通用装配器**:`recon-scenario/dsl/` 的 `ScenarioDefinition` + `GenericScenarioAssembler`,parity 锁定「数据装配 MARKETING_3WAY ≡ 硬编码」。
  - **Phase 2 ✅ 配置存储**:Flyway `V4 recon_scenario_def` + `ScenarioDefinitionCodec`(Jackson + 装配校验)+ `JdbcScenarioDefinitionStore`。
  - **Phase 3a ✅ 种子入库**:`MarketingThreeWayDefinition.seed()` + `ScenarioDefinitionSeeder`(启动幂等 seed);「场景=数据」在管理层成立,未改发起路径。
  - **Phase 3b ✅ 通用执行引擎(XL 核心)**:`ConfigScenarioService` + 动态 `genericReconJob`(`GenericReconJobConfig` + `SegmentStampListener`,每 run 按 scenarioCode 从配置装配)+ `ReconLaunchService` 路由(内置→硬编码 job;配置场景→通用引擎;未知 fail-fast)。**`NewScenarioConfigDrivenTest` 证明 Java 零硬编码场景码 `MKT_3WAY_V2` 纯配置端到端跑通**;`GenericReconJobParityTest` 证明通用引擎 ≡ 硬编码。仅剩形态限制(固定 2 段)。
  - **Phase 4 ✅ 后端 API + 管理 UI**:后端 `ScenarioAdminController`(list/get/put,读 recon.read / 写 recon.launch,装配校验);前端 recon-console「场景管理」页(`/scenarios` 列表 + `ScenarioEditorDrawer` 编辑抽屉,JSON `Input.TextArea` + **原始文本提交**根治金额精度、写操作 `can('recon.launch')` 门控)。经 frontend-plan 全流程(五路子代理→决策记录→计划→独立评审→用户批准),计划见 `docs/plans/b4-scenario-ui-0819-1243/`。pnpm test 25/25 + build + e2e 6/6(双视口)。
  - **Phase 4 ⏳ 管理 UI**:场景 CRUD 页(走 frontend-plan,权限 `recon.admin`)。
- **依赖**:受益于 B2(规则,已完成)+ A1(角色,已完成)+ 管理 UI。

### B5 · Flowable 工单落地 — P2 · 工作量 M–L — ✅ 已完成(2026-08-19)

- **原现状**:`recon-handler` 中仅 Flowable no-op 占位。
- **已交付**:**新外圈模块 `recon-workflow-flowable`**(`org.flowable` 封装其内,组合根 ArchUnit 仍禁直接依赖)——
  Flowable BPMN **冲正审批工作流**(`reversal-approval.bpmn20.xml`:提交 → 人工审批 UserTask → gateway → CONFIRMED/DISCARDED),
  `ReversalApprovalWorkflow`(submit/listPending/decide)+ 结束监听器经 `ReversalDecisionSink` 回写 `reversal_suggestion.status`(ADR-7 只改状态)。
  引擎 **config 门控默认关**(`recon.workflow.flowable.enabled`),用**独立引擎 DB**(避与业务 H2 MODE=MySQL 冲突、不触发 DataSource 自动配置退避);未启用调 API fail-fast。
  组合根 `WorkflowConfig`(条件装配)+ `ReversalApprovalController`(读 recon.read / 提交·审批 recon.dispose)。
- **落地**:`recon-workflow-flowable/*`、`recon-core` `ReversalSuggestionRepository.updateStatus` + JDBC 实现、`recon-batch/.../config/WorkflowConfig`、`web/ReversalApprovalController`、安全 matcher。
- **验证**:模块隔离 `ReversalApprovalWorkflowTest` 3/3 + ArchUnit;组合根门控 `ReversalApprovalWorkflowIT` 2/2(approve→CONFIRMED / reject→DISCARDED)。全量 9 模块绿,默认关时对既有 128 测试零影响。
- **依赖**:A1 鉴权(已就绪);为 B3 审批环节铺路。**诚实边界**:仅冲正审批打样(处置核销仍走 ManualClearingService);资金动作归 B3;单级审批;前端审批页后续。

### B3 · 自动冲正执行 + 审批 — P2(高价值高风险)· 工作量 L — ✅ 已完成(2026-08-19)

- **原现状**:冲正只生成 `SUGGESTED`,无资金动作。
- **已交付**:冲正生命周期打通 `SUGGESTED → CONFIRMED/DISCARDED`(B5 审批)`→ EXECUTED/EXECUTION_FAILED`(B3 执行)。
  - **`ReversalExecutor` SPI(recon-core)**:全系统唯一真正动钱的插件;默认 `LoggingReversalExecutor`**不动真钱**(仅日志),生产以 `@Primary` 接真实清结算适配器覆盖(同 `AlertDispatcher` 范式)。
  - **`ReversalExecutionService`(编排)**:护栏 —— 仅 `CONFIRMED` 可执行(非 CONFIRMED fail-fast);已 `EXECUTED` 幂等跳过(不重复动钱);审批(B5,recon.dispose)与执行(recon.launch)**两个独立控制点**;执行器失败置 `EXECUTION_FAILED` 不吞异常;审计 `discrepancy_action(REVERSAL_EXECUTED)`。
  - **REST** `POST /recon/reversal-executions/{id}/execute`(recon.launch);`ReversalStatus` 加 EXECUTED/EXECUTION_FAILED;`ReversalSuggestionRepository.find/updateStatus`。
- **落地**:`recon-core` `spi/ReversalExecutor`、`ReversalStatus`、`DiscrepancyActionType.REVERSAL_EXECUTED`、端口扩展;`recon-batch` `service/{LoggingReversalExecutor,ReversalExecutionService}`、`web/ReversalExecutionController`、JDBC find、安全 matcher。
- **验证**:`ReversalExecutionServiceTest` 5/5(执行/拒绝/幂等/未找到/失败);全量 9 模块绿(recon-batch 136)。
- **依赖**:A1 鉴权 + B5 工单(均已就绪)。**诚实边界**:真实清结算适配器是可插拔集成点(不在本仓库,同 AlertDispatcher);执行为显式触发(不随审批自动动钱,安全)。

### B6 · 跨币种换算 + FX_RATE_DIFF — P3(自包含)· 工作量 M — ✅ 已完成(2026-08-19)

- **原现状**:`fx_rate` / `base_amount_minor` 字段已留,只读不参与比较。
- **已交付**:`MatchGroup` 新增按侧基准币金额聚合(`GroupAggregator.sumBase`,某侧任一记录缺 base 则该侧为 null);
  `EvaluationContext.fxToleranceMinor`;`DiscrepancyClassifier` 跨币分支细化 —— 两侧基准额均可用时,`|左基准-右基准|≤容差` 视为汇率对上(干净匹配),超容差判 **FX_RATE_DIFF**(激活既有留位枚举);无基准额退回 `CURRENCY_MISMATCH`(原行为)。
  **守恒不受影响**:FX_RATE_DIFF 只是跨币分支的诊断细化,左右原币额仍分落各自币种桶。
- **落地**:`recon-core` `MatchGroup`(base 字段)、`GroupAggregator`、`EvaluationContext`、`DiscrepancyClassifier`(fxRateDiff 分支)。
- **验证**:`FxRateDiffClassifierTest` 4/4(容差内匹配 / 超容差 FX_RATE_DIFF / 无基准回退 / 严格 0 容差);全量 9 模块绿零回归(既有跨币测试无 base → 行为不变)。
- **依赖**:无。**诚实边界**:base_amount_minor 由上游装载填充(源投影层接汇率),本期只做「有基准额则按基准额判」的判定侧;不做汇率获取/换算本身(接汇率源属装载层)。

### B7 · 1:N 明细下钻 + SEG2 roll-up — P3 · 工作量 M — 🟢 明细下钻已完成(2026-08-19)

- **原现状**:只到发放单级总额。
- **已交付(明细下钻)**:只读 `GET /recon/runs/{id}/records?segmentId=&groupKey=`(recon.read)→ 组底层 staged `recon_record` 明细(左右两侧,金额十进制字符串,有界 500 + truncated 标记)。从「组级总额」下钻到「逐条记录」,对任意段/group_key 通用(SEG2 按 channel_serial_no 下钻天然覆盖)。
  - **落地**:`ReconConsoleQueryRepository`(`GroupRecordDetail/GroupRecordReport` DTO + `findGroupRecords`)、`JdbcReconConsoleQueryStore`、`ReconConsoleQueryService.groupRecords`、`ReconConsoleController`。测试 `GroupRecordsDrillDownTest` 2/2;9 模块绿。
- **⏳ 剩余**:SEG2 **按发放单(order_no)跨渠道流水号**的 roll-up —— 需 spine 账务的 order_no↔channel_serial_no 映射做跨段聚合(比单段下钻复杂),作为后续小项;通用明细下钻已覆盖核心价值。
- **依赖**:无强前置。

### B8 · Flink / Kafka 流式 — P4(远期)· 工作量 XL — 🟢 流式内核已落地(2026-08-19)

- **原现状**:批处理(Spring Batch)。
- **已交付(流式内核 walking-skeleton)**:`StreamingReconciler`(recon-core 纯领域)——逐条 `accept` 增量按 match_key 累计
  (不全量 load,只持流式聚合,守恒红线一致),窗口 `flush` 复用**与批完全相同**的 `GroupAggregator + DiscrepancyClassifier`
  → 流式结果与批逐条一致(同一判差内核,无第二套逻辑);null 键逐条单边路由。测试 `StreamingReconcilerTest` 3/3
  (增量→flush 与批一致 / null 键单边 / 多行组聚合);recon-core 纯度门禁未破,9 模块绿。
- **⏳ 诚实边界(基础设施属集成点,不在本仓库)**:真正的分布式流式运行时(Kafka 源 topic + Flink 作业 + exactly-once
  状态后端 + 事件时间/水位线/迟到处理)是可插拔集成层,由外部驱动 `accept()`/`flush()`。本期落地领域内核(批/流共享判差不变量),
  未引入 Kafka/Flink 依赖(P4 远期、条件性「除非有明确实时对账需求」,不为其重构基础设施)。
- **依赖**:最低优先。

---

## 关键跨 Track 硬依赖

**A1 鉴权是 B3(自动冲正)与 B5(工单审批)的硬前置。** 资金动作与审批必须绑定可信身份和权限——「谁批的这笔钱」没有 auth 就无从谈起。即便按功能价值 B3 很诱人,也必须等 A1 落地才能启动。

```
A1 认证鉴权 ─────▶ B5 工单审批 ─────▶ B3 自动冲正执行
```

---

## 推荐执行顺序(综合价值与依赖)

1. **铺地基** — A1 鉴权(阻断项 + 最高价值前置)
2. **并行加固** — A2 dispatcher + A3 真库 + A4 可观测性(可并行)
3. **功能速赢** — B1 三方合并视图(纯读,不依赖 auth,可与 Track A 并行)
4. **规则平台化** — B2 Drools → B4 配置驱动(核心卖点)
5. **资金闭环**(auth 就绪后)— B5 Flowable + B3 自动冲正
6. **按需展开** — B6 FX / B7 下钻 / B8 流式 / A5 KI 加固

---

## 现状核实 · 基于当前仓库(2026-08-18)

| 类别 | 事实 |
|---|---|
| 鉴权 | 后端零 Spring Security;operator 来自前端 `sessionStorage` 手填 |
| 告警 | **A2 已补** `WebhookAlertDispatcher`(配 `RECON_ALERT_WEBHOOK_URL` 即 `@Primary` 生效,发外部 HTTP);未配则 `LoggingAlertDispatcher` 兜底 |
| 部署 | 基座 `compose.yml` 后端跑 H2 file;**A3 已补** `compose.mysql.yml` 叠加层(真 MySQL 8 端到端)+ PG 驱动提为 runtime,真库端到端经 `RealDbEndToEndIT` 验证 |
| 监控 | **A4 已补** actuator(health/liveness/readiness)+ Micrometer→Prometheus(`recon_job_failures_total`/`recon_job_duration` + `spring_batch_job_*`)+ 结构化日志(secure=JSON) |
| 已交付 | M0–M6 全链路 + 前端 Console MVP + M7 本地 Docker 编排 + CI(`ci.yml`) |
