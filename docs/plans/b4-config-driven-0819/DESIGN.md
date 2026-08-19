# B4 · 配置驱动场景 / DSL 规则平台 — 设计与分期(XL,核心卖点)

> 路线图 Track B · B4(P2 核心卖点,工作量 XL)。目标:**不改代码接入新对账场景**。
> 现状:场景硬编码在 `recon-scenario`(`MarketingThreeWayScenario.of(...)` 手工装配 SegmentSpec/描述符/KeySpec)。
> 依赖:受益于 B2(规则,已完成)+ A1(角色,已完成)+ 管理 UI。

## 1. 关键洞察 —— 场景已「几乎是数据」

现有装配已把场景表达为记录:
- `SegmentDef` = `SegmentSpec`(segmentId/left·right·spineRole/stageLabel/keyExtractorId/matchStrategyId/evaluatorId) + 左右 `SourceDescriptor` + `DiscrepancyRule`。
- `SpineBridgeKeyExtractor` = 每段 `KeySpec`(segmentId/matchKeyField/groupKeyField)。
- `SourceDescriptor` = (sourceType, params:表名+列映射+键列)。

**硬编码的只是 `MarketingThreeWayScenario.of()` 里那段「把具体 role/field/表拼进上述记录」的 Java。**
B4 = 把这段拼装从 Java 挪到**声明式定义(DSL)** + **通用装配器** + **配置存储** + **管理 UI**。

## 2. 分期(XL 拆成可独立验收的阶段)

### Phase 1 · 声明式场景模型 + 通用装配器 — ✅ 已完成(2026-08-19)

**已交付**:`recon-scenario/dsl/` 下 `ScenarioDefinition`(含嵌套 `Segment`/`Source`/`Rule` 记录)、
`GenericScenarioAssembler.assemble(def) -> AssembledScenario`、parity 测试 `GenericScenarioAssemblerTest`
(5 例:装配 MARKETING_3WAY 逐字段 == `MarketingThreeWayScenario`、TOLERANCE 规则映射、enabled 子集、
键字段空/空段 fail-fast)。纯 Java 零框架,recon-scenario 门禁未破,`./mvnw -pl recon-scenario test` 18 绿。
下方为原设计(保留作 Phase 2–4 蓝图):


- 在 `recon-scenario` 加纯 Java 定义模型:`ScenarioDefinition{code, segments[]}`、
  `SegmentDefinition{id,leftRole,rightRole,spineRole,stageLabel,matchKeyField,groupKeyField,left:SourceDefinition,right:SourceDefinition,rule:RuleDefinition}`、
  `SourceDefinition{sourceType,params}`、`RuleDefinition{evaluatorType,absToleranceMinor,ratioToleranceBps,enabledTypes}`。
- `GenericScenarioAssembler.assemble(ScenarioDefinition) -> AssembledScenario{segments:List<SegmentDef>, extractor:SpineBridgeKeyExtractor}` ——
  通用地生成今天 `MarketingThreeWayScenario` 手拼出的同一批 SegmentDef/Spec/KeySpec。
- **parity 测试(核心)**:用定义模型描述 MARKETING_3WAY,`assemble` 后逐字段等于 `MarketingThreeWayScenario.of(SourceConfig)` 的产物(segmentId/roles/stage/keyExtractor/matchStrategy/evaluatorId/描述符/rule)。
  → 证明「场景=数据 + 通用装配」与既有硬编码等价,不改判差/守恒。
- 纯 Java、零框架,recon-scenario 门禁不破;不引入 Jackson(JSON 解析在组合根)。

### Phase 2 · 配置存储 + 序列化(组合根)— ✅ 已完成(2026-08-19)
- **已交付**:Flyway `V4__scenario_def.sql`(`recon_scenario_def(code PK, version, definition_json TEXT, enabled, created_at, updated_at)`,三方言通用);
  `ScenarioDefinitionCodec`(Jackson ↔ JSON + 反序列化后 `GenericScenarioAssembler` 装配校验 fail-fast);
  `ScenarioDefinitionStore` 端口 + `JdbcScenarioDefinitionStore`(可移植 update-else-insert upsert、版本自增、坏定义不入库)。
- **测试**:`ScenarioDefinitionCodecTest`(3:round-trip / 结构非法 fail-fast / 坏 JSON)、`JdbcScenarioDefinitionStoreTest`(4:save·find / 版本自增 / list 排序 / 坏定义不入库)。recon-batch 118 绿。
- 修一处潜在坑:`ScenarioDefinition.Rule` 用 `Set.copyOf`(非 `EnumSet.copyOf`,后者对空集抛异常)。

### Phase 3 · 启动/发起按 code 从配置装配

**⚠️ 架构现实(Phase 3 的真难点)**:Spring Batch Job 是**启动期静态 bean**(`marketingThreeWayJob` 在
`MarketingThreeWayConfig` 装配),不是每次发起时按 DB 配置动态构建。所以「不改代码跑任意新场景」的完全形态需要一个
**通用执行引擎**(据 `AssembledScenario` 动态构建 load→matchEvaluate→report 的 Step),这是 XL 里最大的一块,单列子阶段。

分两步降风险:
- **Phase 3a — ✅ 已完成(2026-08-19)**:`MarketingThreeWayDefinition.seed()`(recon-scenario,把硬编码场景表达为声明式数据,
  parity 测试证明 `assemble(seed()) ≡ 硬编码`)+ `ScenarioDefinitionSeeder`(ApplicationRunner,启动期幂等 seed 进配置存储)。
  「场景=数据」在管理/校验层成立,**未改发起路径**,现有测试全绿(recon-scenario 19 / recon-batch 120)。
  ⏳ 未做:发起时 `ReconLaunchService` 从存储硬校验 code+enabled —— 留待与 Phase 3b 一起(避免仅校验不执行的割裂)。
- **Phase 3b(generic execution engine,XL 核心)** —— 🟡 桥已建 / 动态 Job 待落地:
  - ✅ **配置→场景桥**:`ConfigScenarioService.assemble(code)`(store → 校验 enabled → `GenericScenarioAssembler` → `AssembledScenario`);不存在 404、停用 fail-fast。测试 `ConfigScenarioServiceTest` 4 例。这是「发起按 code 从配置装配」的读侧入口。
  - ✅ **动态 Job(2026-08-19 落地,parity 通过)**:`GenericReconJobConfig` 的 `genericReconJob` 据 `AssembledScenario`
    逐段动态编排 `prepare → (load→matchEvaluate)×N → report → convergence → alertRelay`。`SegmentStampListener` 在
    load/manager step 的 `beforeStep` 写 `segmentId`,共享 @StepScope `generic*` 组件按段解析;附加式(不动既有
    `marketingThreeWayJob`)。`GenericReconJobParityTest` 用同数据集断言分类/bridge 归因/双向守恒与硬编码 job **逐项一致**;
    全量 8 模块绿(recon-batch 125),零回归。
  - ✅ **每 run 按 scenarioCode 解析 + 发起路由(2026-08-19 落地)**:`generic*` @StepScope 组件据
    `ConfigScenarioService.assemble(scenarioCode)` 每 run 从存储装配场景(step 结构按内置形态固定 {@code EXPECTED_SEGMENTS=2},
    `SegmentStampListener` 改为写 `segmentIndex`);`ReconLaunchService` 路由:内置 MARKETING_3WAY→硬编码 job(行为不变),
    其它 code 须在存储中启用且段数匹配→`genericReconJob`,否则 fail-fast。
  - ✅ **端到端证明**:`NewScenarioConfigDrivenTest` —— 场景码 `MKT_3WAY_V2` **Java 里零硬编码**、纯配置,
    经通用引擎跑通 + 经 `ReconLaunchService` 按 code 路由跑通 + 未知码 fail-fast。`GenericReconJobParityTest` 证明通用引擎
    (从配置解析 MARKETING_3WAY)≡ 硬编码 job。全量 8 模块绿(recon-batch 128),零回归。
  - **⏳ 仅剩形态限制(记录)**:通用引擎 step 结构固定为 2 段形态;非 2 段场景需另一形态的通用 job(未来)。
  - 原 de-risked 实现路径(已按此落地):
    1. **复用既有共享步**:`prepareRunStep`/`reportStep`(已 segment-agnostic,读全部 `recon_report_partial`)/`convergenceStep`/`alertRelayStep`/`stagingWriter`/`skewDetector`/`reconPartitionTaskExecutor` 直接复用。
    2. **match 侧零改**:`m4*` @StepScope worker(reader/processor/writer)已按 `stepExecutionContext['segmentId']` 解析,天生 N-generic;每段程序化建独立命名 worker step `<segId>MatchWorkerStep` + partitioner(带 segmentId)。
    3. **load 侧 @StepScope 难点解法**:load step 非分区、无 partition context。做一个共享 @StepScope `genericLoadReader`/`genericStandardizeProcessor` 读 `#{stepExecutionContext['segmentId']}`,每段 load step 挂一个 `SegmentStampListener(segId)`(`beforeStep` 把 segmentId 写入 step 上下文,早于 @StepScope reader 首次 open)→ 共享组件按段解析 SegmentDef(来自注入的 `AssembledScenario`)。
    4. **段定义来源**:`ScenarioDefinitionSeeder` 已保证 MARKETING_3WAY 在库;通用 Job 用 `ConfigScenarioService.assemble(code)` 取 `AssembledScenario`(context 启动期可读库,或直接用 `MarketingThreeWayDefinition.seed()` 兜底)。
    5. **parity 锁定**:新 `genericReconJob` **附加**注册(不动既有 `marketingThreeWayJob`,零回归),用 `AbstractThreeWayJobIT` 同数据集跑通用 Job,断言 discrepancy 分类/守恒/bridge_break_stage 与既有 job **逐项一致**;绿后再让发起路由到通用引擎。
  - **风险**:Spring Batch @StepScope 晚绑定时序、restart/skew/sub-bucket 语义须逐项回归;故独立小步落地 + 附加式(不改既有 job)。
- 与 B2 融合:段 `rule.evaluatorType=DROOLS` + `recon.rules.drools.extra-classpath`(或每场景 DRL 引用)即规则可配。

### Phase 4 · 场景管理 API(后端 ✅)+ 管理 UI(前端 ⏳ 走 frontend-plan)
- ✅ **后端 REST**:`ScenarioAdminController` —— `GET /recon/scenarios`(list,recon.read)、`GET /recon/scenarios/{code}`
  (detail,recon.read,404)、`PUT /recon/scenarios/{code}?enabled=`(upsert,**recon.launch**,装配校验 400,code 不符 400)。
  安全 matcher 在 `CasdoorSecurityConfig`(写 recon.launch / 读 recon.read)。测试 `ScenarioAdminControllerTest`。recon-batch 131 绿。
- ✅ **前端(2026-08-19)**:recon-console「场景管理」页(`/scenarios`)—— `ScenariosPage` 列表(Table/移动卡)+
  `ScenarioEditorDrawer` 编辑抽屉(`Input.TextArea` JSON,**提交原始文本**根治精度,`can('recon.launch')` 门控,
  destroyOnHidden + ref-guard 播种防串场景,enabled 从 detail 播种防静默翻转,新建判重 Modal.confirm)。
  经 frontend-plan 全流程,计划见 `docs/plans/b4-scenario-ui-0819-1243/`;pnpm test 25/25 + build + e2e 6/6。
  **诚实边界**:读侧 axios JSON.parse 对 >2^53 有损(容差现实小额,记为已知限制)。

## 3. 架构约束

- 定义模型 + 通用装配器落 `recon-scenario`(纯 Java,依赖仅 recon-core)。JSON/YAML 解析与存储落组合根 recon-batch。
- 不破坏:MARKETING_3WAY 现有硬编码路径与全部集成测试在 Phase 1–3 期间保持可用(种子定义等价);
  硬编码 `MarketingThreeWayScenario` 在 Phase 3 后可保留为「内置定义的生成器」或逐步弃用(不急删)。
- 安全关键不变量(fingerprint/守恒/refine)全部沿用 recon-core,DSL 只描述装配,不触判差算法。

## 4. 本设计先交付：Phase 1

Phase 1 是「配置驱动」的地基与最高价值验证点(证明通用装配 ≡ 硬编码)。Phase 2–4 依赖它。
后续阶段按此文档逐个落地并各自 `./mvnw test` 绿 + 前端 frontend-plan。

## 5. 非目标(本里程碑)

- 不做可视化拖拽建模(JSON 编辑够用);不做多租户场景隔离;不做场景版本回滚 UI(存 version 字段留位)。
- 不改 recon-core 判差/守恒;不动 B2 的 DRL 语义。
