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

### A2 · 生产级 AlertDispatcher — P1 · 工作量 S

- **现状**:只有 `LoggingAlertDispatcher`(仅打日志),告警到不了外部。
- **要做**:用 `@Primary` 替换为真实 webhook / 邮件 / IM;outbox + 中继机制已就绪,只差可插拔 dispatcher。
- **依赖**:密钥/配置管理(A4);低耦合,可独立交付。

### A3 · 真库端到端 + 生产 DB profile — P1 · 工作量 M

- **现状**:`compose.yml` 后端跑 H2 file,默认测试 H2,真库仅 Testcontainers(KI-4)。
- **要做**:跑通 `CollationRealDbIT`(MySQL8 + PG),验证 V3 collation ALTER、`fetchSize=Integer.MIN_VALUE` 真流式、方言 batch 元数据;补生产 DB 连接 profile。
- **依赖**:需真实 MySQL/PG 环境或 CI Docker。

### A4 · 可观测性 + 健康检查 + 配置/密钥 — P1 · 工作量 M

- **现状**:无 actuator / micrometer / prometheus;compose healthcheck 靠打 `/recon/dashboard` 兜底。
- **要做**:接 actuator(liveness / readiness / metrics)+ Micrometer→Prometheus + 批作业失败告警 + 结构化日志;外部化配置与密钥。
- **依赖**:无强前置;其密钥能力被 A2 复用。

### A5 · KI 已知问题加固 — P2(默认关/低危)· 工作量 S–M

- **现状**:均为默认关 / 低危场景,已在 `KNOWN_ISSUES.md` 记录。
- **要做**:KI-1 skew restart 配置指纹 fail-fast(仅 sub-bucket 开启时);KI-6 refine 函数性预校验作业(数据质量护栏)。
- **依赖**:无 —— 不阻断上线,但需 track。

---

## Track B · 平台化功能

> 阶段二 Non-goals,字段/接口已留位。把「营销三方专用」推向「通用对账平台」,按价值/依赖排。

### B1 · 三方合并只读视图 — P1(速赢)· 工作量 S–M

- **现状**:MVP 只出两段独立报表。
- **要做**:把两段独立报表合成单一三方 roll-up 摘要(口径决议 A2 归阶段二);只读查询 + 前端页,**无算法风险**,运营价值高、成本低。
- **依赖**:无(纯读)—— **可与 Track A 并行,适合作为阶段二第一个功能交付**。

### B2 · Drools 规则引擎 — P1 · 工作量 M–L

- **现状**:`DroolsEvaluator` 仅接口,`EvaluatorFactory` 遇 DROOLS fail-fast。
- **要做**:落地 DiscrepancyEvaluator 阶段二,让判差 / 分类规则**可配置化**,不改代码调规则。
- **依赖**:接口已留,无硬前置;是 B4 配置驱动的组成部分。

### B4 · 配置驱动场景 / DSL 规则平台 — P2(核心卖点,工作量最大)· 工作量 XL

- **现状**:场景硬编码装配(`recon-scenario`)。
- **要做**:**不改代码接入新对账场景** —— 平台核心卖点。需配置存储 + 场景装配 DSL + 管理 UI。
- **依赖**:受益于 B2(规则)+ A1(角色)+ 管理 UI;放在有前置铺垫后。

### B5 · Flowable 工单落地 — P2 · 工作量 M–L

- **现状**:`recon-handler` 中仅 Flowable 占位。
- **要做**:落地处置 / 冲正审批工作流。
- **依赖**:与 B3 互为支撑;**需 A1 鉴权**。

### B3 · 自动冲正执行 + 审批 — P2(高价值高风险)· 工作量 L

- **现状**:冲正只生成 `SUGGESTED`,无资金动作。
- **要做**:从「只生成建议」升级到审批流 + 真实资金动作。
- **依赖**:**强依赖 A1(谁有权批钱)** + B5 工单;须在 auth 与工单就绪后做。

### B6 · 跨币种换算 + FX_RATE_DIFF — P3(自包含)· 工作量 M

- **现状**:`fx_rate` / `base_amount_minor` 字段已留,只读不参与比较。
- **要做**:汇率换算 + 容差 + FX_RATE_DIFF 判定;自包含算法,只在多币种对账才需要。
- **依赖**:无。

### B7 · 1:N 明细下钻 + SEG2 roll-up — P3 · 工作量 M

- **现状**:只到发放单级总额。
- **要做**:明细级下钻 + SEG2 发放单跨渠道流水号 roll-up。
- **依赖**:无强前置。

### B8 · Flink / Kafka 流式 — P4(远期)· 工作量 XL

- **现状**:批处理(Spring Batch)。
- **要做**:从批处理转近实时 / 流式对账,基础设施大改。
- **依赖**:最低优先 —— 除非有明确实时对账需求。

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
| 告警 | 仅 `LoggingAlertDispatcher`(打日志) |
| 部署 | `compose.yml` 后端跑 H2 file,非 MySQL / PG |
| 监控 | 无 actuator / micrometer / prometheus |
| 已交付 | M0–M6 全链路 + 前端 Console MVP + M7 本地 Docker 编排 + CI(`ci.yml`) |
