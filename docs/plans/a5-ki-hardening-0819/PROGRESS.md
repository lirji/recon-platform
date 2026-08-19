# A5 KI 已知问题加固 · 实施进度

> 路线图 `docs/PHASE2_ROADMAP.md` Track A · A5(P2 默认关/低危 · 工作量 S–M)。**已完成 2026-08-19**。
> 两项:KI-1 skew restart 配置 fail-fast;KI-6 refine 函数性诊断护栏。

## KI-1 · sub-bucket restart 配置变 → 静默错算 → fail-fast

- **问题**:`recon.skew.sub-bucket.enabled=true`(默认关)下,failed Run restart 前改 skew 配置,残留局部 partial 与新形状混存,左右同比例膨胀使 residual≡0 骗过守恒,静默标 balanced。
- **交付**:`job/SkewConfigGuardListener.java`(挂 `reconciliationJob`/`marketingThreeWayJob`)。
  - 形状指纹 `enabled|fanout` 写入 **Job 级 ExecutionContext**(跨 restart 由 Spring Batch 复制持久化);`beforeJob` 与上次比对。
  - **fail-fast 条件**(KI-1 明列的两个残留):① fanout 数值变(两次均 enabled 且 fanout 不同);② 累计形状翻转 ≥2(多次连续翻转)。
  - **放行**:单次整桶↔sub 翻转(fanout 不变)—— 已由 `MatchEvaluateWriter` worker 级 stale-partial 清理覆盖;守卫不误伤(否则会破坏 `ReconJobShapeFlipRestartTest`)。
  - sub-bucket 默认关 → 形状恒定 → 守卫恒 no-op,零生产影响。
- **测试**:`ReconJobSkewFanoutRestartGuardTest`(fanout 8→4 restart → fail-fast,含 "KI-1" 消息)+ `ReconJobShapeFlipRestartTest`(单次翻转仍 COMPLETED)共同界定守卫边界。**均绿**。
- **边界**:守卫把静默错算升级为显式 fail-fast;运维「restart 前不改 skew」约束仍作纵深防御。

## KI-6 · 脏跨表数据产假 BRIDGE_BROKEN/EXTRA(守恒抓不到)→ 显式可发现

- **问题**:同一 match_key 两侧挂不同 group_key → 落不同桶 → 永不相遇 → 假 MISSING/BRIDGE_BROKEN + 假 EXTRA;左右额独立入各自口径,双向守恒仍 residual≡0,门禁抓不到。
- **交付**:只读诊断 `GET /recon/runs/{id}/refine-violations`。
  - `JdbcReconConsoleQueryStore.findRefineViolations`:DB 侧 `GROUP BY segment_id, match_key HAVING COUNT(DISTINCT group_key) > 1` 扫 staged `recon_record`(null 键排除,按冲突组数降序)。**不建 Java 全表映射、不占对账热路径**。
  - `ReconConsoleQueryService.refineViolations`:有界 100,超出置 `truncated`(不静默截断);DTO `RefineViolation`/`RefineViolationReport`。
- **测试**:`RefineViolationsTest`(脏键 distinctGroupCount=2 被列出;同 group 干净键 + null 键不误报;干净数据 0 违规)。**绿**。
- **边界**:事后诊断(对已 load 的 staging 扫描),非 join 实时拦截;上游「同一 match_key 唯一 group_key」仍是第一道防线。源表级预扫可后续按需再加。

## 落地文件

- KI-1:`job/SkewConfigGuardListener.java`(新)、`config/BatchConfig.java`、`config/MarketingThreeWayConfig.java`(挂 listener)、`job/ReconJobSkewFanoutRestartGuardTest.java`(新)。
- KI-6:`service/ReconConsoleQueryRepository.java`(DTO+端口)、`persistence/JdbcReconConsoleQueryStore.java`、`service/ReconConsoleQueryService.java`、`web/ReconConsoleController.java`、`web/RefineViolationsTest.java`(新)。
- 文档:`KNOWN_ISSUES.md` KI-1/KI-6、`PHASE2_ROADMAP.md` A5、`README.md` API 表。

## 验证

- KI-1:`ReconJobSkewFanoutRestartGuardTest` 1/1 + `ReconJobShapeFlipRestartTest` 1/1。
- KI-6:`RefineViolationsTest` 2/2 + `ReconConsoleControllerTest`/`ThreeWayRollupTest` 回归绿。
- `./mvnw -q -pl recon-batch -am test` 全绿(含 ArchUnit 门禁)。
