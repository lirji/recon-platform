# B4 · 场景管理前端页 · 决策记录

> 配置驱动平台(B4)的管理 UI。后端 REST 已就绪并测试(`ScenarioAdminController`)。经 frontend-plan 五路只读子代理调查综合。
> 计划批准前不写任何代码。相关:`docs/plans/b4-config-driven-0819/DESIGN.md`(后端平台已完成)。

## 已核实的后端契约(以真实源码为准)

- `GET /recon/scenarios` → **裸数组** `ScenarioSummary[]{code,version,enabled,segmentCount}`(**无分页**,按 code 升序)。
- `GET /recon/scenarios/{code}` → `ScenarioView{code,version,enabled,definition:ScenarioDefinition}`;缺失 404。
- `PUT /recon/scenarios/{code}?enabled=<bool 默认 true>`,body=`ScenarioDefinition` → `ScenarioView`。
  **400**:①path code ≠ body.code;②装配校验失败(重复段/键字段空等)。**无 expectedVersion / 无 409 乐观锁**(version 服务端单调递增,仅展示)。
- 权限:读 `recon.read`(路由守卫已覆盖);**写 `recon.launch`**(CasdoorSecurityConfig matcher)。
- `ScenarioDefinition{code, segments:[{id,leftRole,rightRole,spineRole?,stageLabel,matchKeyField,groupKeyField,left:{sourceType,params},right:{sourceType,params},rule:{evaluatorType,absToleranceMinor,ratioToleranceBps,enabledTypes}}]}`;枚举:`SourceRole=MARKETING|ACCOUNTING|CHANNEL`、`EvaluatorType=EXACT|TOLERANCE|DROOLS`、`DiscrepancyType=10 值`。

**冲突裁决**:UIUX 子代理假设了分页 + 409 乐观锁;需求/架构/测试三方核实后端**无分页、无 409/expectedVersion**。以真实契约为准:列表不接 `Pagination`、不做 409 处理、version 只读展示。

## 关键决策与备选

### D1 · 交互形态 —— **ScenariosPage(列表)+ ScenarioEditorDrawer(查看/编辑),备选 A**

| 备选 | 说明 | 取舍 |
|---|---|---|
| **A(选)列表 + 编辑抽屉** | 逐字复刻 Runs/Discrepancies 的「页 + 抽屉」;宽抽屉(md?900:100%)容纳 JSON | 与既有 2 页零偏差、路由/菜单改动最小、数据小无需分页、回归最低 |
| B 列表 + 独立编辑路由 `/scenarios/:code` | 可深链、编辑区更大 | 偏离既有抽屉范式、多挂一条 lazy 路由、丢列表上下文;对小数据 upsert 过度设计 |
| C 全在一页用 Modal | 新建最简 | Modal 承载大 JSON 局促;仓库 Modal 留给小表单(发起对账),Drawer 承载详情/编辑 |

### D2 · JSON 编辑 + 精度 —— **`Input.TextArea` + 提交「原始文本」(不引 monaco)**

- 仓库**无代码编辑器依赖**;用 `Input.TextArea`(`mono` 等宽,`autoSize {minRows:10,maxRows:24}`),打开时 `JSON.stringify(definition, null, 2)` 播种。
- **精度关键决策**:`absToleranceMinor` 是后端 `long`→JSON number,`JSON.parse` 会对 `>2^53` 丢精度(违反仓库「金额禁转 number」纪律)。
  **解法:PUT body 直接发用户输入的原始 JSON 文本**(axios 对 string body 不再 stringify,配 `Content-Type: application/json`),`JSON.parse` **仅用于**①语法校验(失败内联报错、不提交)②读出 `code` 做客户端 path/code 一致性预检。**不 re-serialize**,故大数原样透传、零精度损失。
- 装配级语义校验(重复段等)靠后端 400 兜底,经 `errorMessage(error)` 展示。

### D3 · enabled 改动位置 —— **仅在编辑抽屉内(不做列表行内即时开关)**

- 列表 `ScenarioSummary` **不含 definition**,而 PUT 必带完整 definition;行内切换需「先 GET definition 再 PUT」两跳,且易踩 `enabled` 缺省 true 陷阱。
- 决定:列表里 enabled 用 **Tag+文字**(启用绿/停用灰,颜色非唯一手段)只读展示;改 enabled 在编辑抽屉内(此时 definition 已加载),用 `Switch`(`checkedChildren=启用/unCheckedChildren=停用`)。
- **enabled 缺省陷阱**:`saveScenario` 恒显式传当前 enabled,绝不依赖后端默认 true(测试锁定)。

### D4 · 新建 —— **同一抽屉「新建模式」:code 可编辑 + 模板骨架预填**

- 后端只有 upsert PUT。新建 = PageHeader「新建场景」按钮(`can('recon.launch')` 门控)打开抽屉的新建模式:code 输入(必填 + 大写下划线正则)、JSON 预填一段最小模板骨架降门槛。
- **防呆**:新建保存前用已加载的列表数据客户端判重,若 code 已存在给 `Popconfirm`「已存在,将覆盖?」(upsert 会覆盖)。编辑模式 code 禁改。

### D5 · 内置场景 MARKETING_3WAY

- 列表给「内置」标记。允许编辑(它只是配置数据);注意:`ReconLaunchService` 对 MARKETING_3WAY 路由到**硬编码 job**(不查存储),故在管理台停用它**不影响**内置发起(仅影响以它为模板的理解)。文档标注,不加硬保护(MVP)。

### D6 · 路由/菜单/权限

- `router.tsx` 加 `/scenarios` lazy 子路由,**沿用 `RequireAuth recon.read`**(不加 launch 守卫——否则误挡只读 viewer);写控件组件内 `can('recon.launch')` 门控(隐藏而非禁用,沿用既有范式)。
- `AppLayout.navigation` 加 `{key:'/scenarios', label:'场景管理', icon:<SettingOutlined/>}`;`startsWith` 高亮与面包屑自动生效。

### D7 · 与运行管理的闭环(本期非目标,记录)

- RunsPage/LaunchRunModal 的 scenarioCode 下拉目前硬编码 MARKETING_3WAY;未来可改由 `listScenarios()`(enabled)驱动。**本期不改**(避免扩大 blast radius),列为后续。

## 假设(未确认即标注)

1. 场景数量小(个位~数十),列表裸数组无需分页/虚拟化。
2. 目标用户(持 recon.launch 的管理员)接受半结构化 JSON 编辑,MVP 不做逐字段结构化表单/可视化构建器。
3. 后端 400 消息可读、可直接透出(`errorMessage`);若为英文技术串,体验可后续优化。
4. `absToleranceMinor` 现实取值小;原始文本提交方案对任意大数也安全(不依赖此假设)。

## 待用户批准时确认

- D1 交互形态(列表 + 编辑抽屉)是否认可?
- D2 JSON 用 `TextArea` + 原始文本提交(不引 monaco)是否认可?若要语法高亮需新增依赖(monaco/codemirror),属范围外。
- D3 enabled 仅在抽屉内改(不做列表行内开关)是否认可?
