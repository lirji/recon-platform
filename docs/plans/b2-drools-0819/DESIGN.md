# B2 · Drools 规则引擎判差 — 设计定稿(阶段二)

> 路线图 Track B · B2(P1 · 工作量 M–L)。目标:**判差/分类策略可配置化,不改 Java 代码即可调规则**。
> 现状:`DroolsEvaluator` 仅接口,`EvaluatorFactory` 遇 `DROOLS` 抛 `UnsupportedOperationException` fail-fast。
> 口径来源:CLAUDE.md「Drools 预留」「ArchUnit 门禁」;设计定稿 ADR-8。

## 1. 硬约束(不可违反)

- **ArchUnit 红线**:recon-core 的 `..domain..`/`..spi..`/`..application..` **禁 `org.kie..`/`org.drools..`**。
  → Drools 实现**必须落外圈新模块**,不能进 recon-core。依赖箭头仍指向 core。
- **判差红线(ADR-8)**:装配/运行遇规则加载失败 **fail-fast,绝不静默跳过判差**。
- **不改守恒/指纹不变量**:`DiscrepancyClassifier` 的 fingerprint 构造、bridge 归因、null-key 鉴别量、
  守恒双向口径是安全关键,**不在 DRL 里重写**(重写=parity 噩梦 + 破坏 A1 人工处置 re-link)。

## 2. 核心设计决策

### D1 · 新模块 `recon-rules-drools`(外圈)
- 依赖:仅 `recon-core`(用 SPI `DroolsEvaluator`/`DiscrepancyEvaluator` + 领域模型 + 复用 `DiscrepancyClassifier`)。
- 内容:`DroolsDiscrepancyEvaluator implements DroolsEvaluator`、DRL 规则(`src/main/resources/rules/`)、KieContainer 引导。
- 自带 `ArchitectureTest`:允许 `org.kie..`/`org.drools..`,但**禁 Spring/Batch/JDBC**(保持纯规则库,组合根才接 Spring)。
- 加入根 `pom.xml` `<module>`。

### D2 · DRL 作「策略层」,不重写分类器(关键安全决策)
`DroolsDiscrepancyEvaluator.evaluate(group, rule, ctx)`:
1. 先调既有 `DiscrepancyClassifier.classify(group, ctx)` 得**候选** discrepancy(保留全部安全关键构造:fingerprint/bridge/amount)。
2. 构造可变 fact `DiscrepancyDecision`(候选 type 可空=干净、deltaAbs、currency、isMultiLine、absTolerance、ratioBps、enabled 集…)+ 只读 `RuleContext`(scenarioCode/period/segmentId)。
3. `kieSession.insert(decision); insert(ruleContext); fireAllRules()` —— DRL 规则**改写** decision(suppress/降级/改判/打 severity)。
4. 读回 `decision.finalType`:null → 干净(返回空);否则用候选 discrepancy(必要时 `withType(finalType)`)返回单条。

**为何这样**:安全关键构造留在可信 Java;DRL 只承载**热可编辑的业务策略**(如「AMOUNT_MISMATCH 且 |delta|≤阈值 且 status=PENDING → 降为 TIMING」「场景 X 账期 Y 抑制 DUPLICATE」)——这正是平台卖点(通向 B4),又不动守恒/指纹。

### D3 · 默认规则集 = EXACT+enable+tolerance 的等价复刻(parity 保证)
`rules/discrepancy-default.drl` 默认规则**只做**:①候选 type 被 `rule.enabled` 关掉 → suppress;②AMOUNT_MISMATCH 且 |delta|≤`absTolerance` 或 ≤`ratioBps` → suppress(等价 ToleranceEvaluator)。
→ 默认装配下 **Drools 判差 ≡ ExactEvaluator/ToleranceEvaluator**,由 parity 测试锁定;ops 追加规则才改变行为。

### D4 · 组合根装配(不破坏 core 红线)
- `EvaluatorFactory`(core)**保持** DROOLS 抛异常(core 造不出 Drools)。
- recon-batch 依赖 `recon-rules-drools`;新增 batch 层 `EvaluatorResolver`:EXACT/TOLERANCE → `EvaluatorFactory.create()`;DROOLS → 注入的 `DroolsDiscrepancyEvaluator` bean。
- `BatchConfig` / `MarketingThreeWayConfig` 里 `new EvaluateProcessor(EvaluatorFactory.create(type), …)` 改为经 `EvaluatorResolver.resolve(type)`。
- `DroolsDiscrepancyEvaluator` 作 `@Bean`(recon-batch config),KieContainer 在构造期编译 DRL;**编译失败 → 启动 fail-fast**(红线)。DRL 路径可配置 `recon.rules.drools.path`(默认 classpath 默认规则集)。

### D5 · fail-safe 语义
- 规则**编译**失败(启动期)→ Bean 创建抛异常 → 应用启动失败(fail-fast,绝不带病判差)。
- 规则**运行**期异常(fireAllRules 抛)→ 向上抛,让批作业 FAILED(不吞、不静默跳过)。
- 不提供「Drools 挂了自动回退 Exact」的静默兜底(违反红线);要回退只能显式改配置 `evaluator-type=EXACT`。

## 3. 组件与文件

| 交付 | 位置 | 说明 |
|---|---|---|
| 新模块 | `recon-rules-drools/pom.xml` | 依赖 recon-core + drools-core/kie(BOM 版本);根 pom 加 module |
| 评估器 | `.../rules/DroolsDiscrepancyEvaluator.java` | 实现 DroolsEvaluator;classifier 候选 + fire DRL |
| Fact | `.../rules/DiscrepancyDecision.java`、`RuleContext.java` | 可变决策 fact + 只读上下文 fact |
| DRL | `.../resources/rules/discrepancy-default.drl` | 默认策略(enable + tolerance suppress) |
| KIE 引导 | `.../rules/DroolsRuleEngine.java`(或直接 KieServices) | 编译 DRL → KieContainer,失败 fail-fast |
| ArchUnit | `.../test/.../ArchitectureTest.java` | 允许 kie/drools,禁 spring/batch/jdbc |
| 组合根 | `recon-batch/.../config/EvaluatorResolver.java` + 改 2 处 config | DROOLS 路由到 Drools bean |
| Discrepancy 增强 | `recon-core/.../Discrepancy` 加 `withType(DiscrepancyType)` | 策略改判时复用候选构造(不改 fingerprint 逻辑?见待决) |

## 4. 待决问题(实现时定,列出不臆造)

1. **改判后的 fingerprint**:DRL 把 AMOUNT_MISMATCH 降级为 TIMING,fingerprint 含 type → 会变。是否允许策略改判影响 fingerprint(影响跨重跑 re-link)?
   **倾向**:B2 默认规则集**不改判、只 suppress**(fingerprint 不受影响);「改判」作为高级能力,fingerprint 用 finalType 重算并在文档标注其对 re-link 的影响。默认路径零风险。
2. **Drools 版本**:drools 8.x(KIE 7→8 命名空间 `org.drools`/`org.kie.api`)。取与 Java 21 兼容的稳定版(如 8.44.x)。用 BOM 管理。
3. **每段一个 KieSession vs 复用**:KieContainer 全局单例(线程安全),每次 evaluate 用 stateless session 或短 stateful session。**倾向 stateless**(无状态、天然并发安全,契合分桶并行)。

## 5. 测试策略

- **parity 测试**(核心):同一批 MatchGroup 分别过 `ExactEvaluator` 与 `DroolsDiscrepancyEvaluator`(默认规则集),断言产出的 Discrepancy **逐字段一致**(type/fingerprint/amount)。复用 `recon-core` 的 `ReconFixtures`。
- **策略测试**:加一条自定义 DRL(suppress DUPLICATE / tolerance 降噪),断言行为按规则变。
- **fail-fast 测试**:坏 DRL → KieContainer 构造抛异常;运行期异常上抛。
- **ArchUnit**:新模块门禁 + 确认 recon-core 门禁不因新增而破。
- **组合根**:`evaluator-type=DROOLS` 时 EvaluateProcessor 用 Drools 评估器(装配测试),Spring 上下文能启动。
- 全量 `./mvnw -q test` 绿(含既有 6 模块 ArchUnit)。

## 6. 非目标

- 不做规则热重载/管理 UI/规则版本化(那是 B4 配置驱动平台)。B2 只做:DRL 装配 + 默认 parity + 可扩展策略层 + fail-fast。
- 不重写 DiscrepancyClassifier 的分类/守恒/指纹逻辑。
- 不改前端。

## 7. 风险与回滚

- 风险:Drools 依赖体积/启动开销;DRL 与 Java 语义漂移(靠 parity 测试 + `drools-rule-check` skill 守)。
- 回滚:配置 `evaluator-type=EXACT`(默认)即完全绕开 Drools;新模块不被组合根引用即死代码。DROOLS 是 opt-in,不影响既有绿。
