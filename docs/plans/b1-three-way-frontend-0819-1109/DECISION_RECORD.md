# B1 三方合并只读视图 · 前端决策记录

> 阶段二 Track B · B1 前端页/区块。后端只读 API(`GET /recon/runs/{id}/three-way` → `ThreeWayReport`)已交付并有契约测试;
> 本记录只覆盖**前端**,经 frontend-plan 五路只读子代理调查综合而成。计划批准前不改任何代码。
> 口径权威:`docs/plans/b1-three-way-rollup-0819/PROGRESS.md`、`docs/design/RECON_MVP_DESIGN.md` A2/ADR-1。

## 后端契约(已核实源码,非臆造)

- `ReconConsoleController.threeWay`:`GET /recon/runs/{id}/three-way`,落只读 `/recon/**`(权限 `recon.read`),无写。
- `ReconConsoleQueryService.threeWayRollup`:纯派生,无新 SQL。**run 不存在 → 404**(`{error:not_found}`);空 runId → 400;
  **非三方 / 未出报表的 run → 200 + `currencies=[]` + `threeWayBalanced=null`(不报错)**。
- DTO:`ThreeWayReport{ runId, scenarioCode, accountingPeriod, status, threeWayBalanced: boolean|null, currencies: CurrencyRollup[] }`;
  `CurrencyRollup{ currency, seg1: ReportEntry|null, seg2: ReportEntry|null, threeWayConsistent: boolean, bridgeBrokenMinor: string(十进制) }`。
  无 `@JsonInclude(NON_NULL)` → 缺段输出 JSON `null`,前端类型须 `ReportEntry | null` 判空。
- 唯一三方场景:`MARKETING_3WAY`(`SEG1_MKT_ACCT` 营销↔账务、`SEG2_ACCT_CHANNEL` 账务↔渠道)。MVP 全部 run 均为此场景。

## 关键决策与备选对比

### D1 · 落点与交互形态 —— **RunDetailDrawer 内 Tabs,「守恒报表」为默认 Tab**

| 备选 | 说明 | 取舍 |
|---|---|---|
| **A(选)Tabs** | 抽屉 body 改 `Tabs`:Tab1「守恒报表」(默认激活=现有表)、Tab2「三方合并」(激活才拉,懒加载) | 数据只在 Run 维度,抽屉已是运行详情规范载体;复用运行信息头;懒加载贴合 code-split;改动可控 |
| B 独立路由/独立抽屉 `/runs/:id/three-way` | 与现有抽屉零耦合、可深链 | 重复运行信息头、多一次导航、为单 Run 维度只读派生新增路由属过度工程,与"抽屉承载详情"的信息架构相悖 |
| C Runs 列表/Dashboard 内联 | 列表加"三方"列 | **否决为主入口**:无批量端点,逐行调 `three-way` = N+1;`threeWayBalanced` 三态仅宜作未来行内指示,不承载明细 |

**硬约束(决定 A 的形态)**:`RunsPage.test.tsx:64-70` 断言开抽屉即见 `运行信息 / 守恒报表 / SEG1_MKT_ACCT`。
故「守恒报表」**必须默认可见**——取"守恒为默认激活 Tab"(而非把三方设默认,后者会因 `getThreeWayReport` 未 mock 且需渲染 SEG1 而破坏该测试)。
AntD Tabs 默认**不挂载**非激活 pane,三方 pane 在 `getThreeWayReport` 上的依赖对该测试零影响,**`RunsPage.test.tsx` 无需改动**。

**避免重复文案**:现有「守恒报表」是 `<h3 className="section-title">`。移入 Tab `label` 后,pane 内**不再重复 h3**,保证 `getByText('守恒报表')` 只命中一处(否则多元素报错)。

**前瞻门控**:仅当 `scenarioCode === 'MARKETING_3WAY'` 渲染 Tabs;其它场景(B4 后才会出现)保持现状单 section(含 h3),不显示空洞的三方 Tab。MVP 现只有该场景,门控为一行防御式代码。

### D2 · 三方 Tab 内的呈现 —— **顶部整体 banner + 每币种一张 Card(段用 Row/Col 而非横滚表)**

- **整体 banner**:`threeWayBalanced` 三态,复用 `RunsPage.tsx:60` 既有范式——`true`→success「三方守恒 · 全平」、`false`→danger「三方不一致」、`null`→中性「无三方报表 / 待生成」;
  **颜色非唯一手段**:每态都配中文文案 + 图标(`CheckCircleOutlined` 既有在用;`CloseCircleOutlined` 需新 import,antd 包内存在)。
  **KPI 只放计数类**:可展示「全平币种数 / 不一致币种数」等计数,**严禁「总桥断额」跨币种金额求和**(各币种 minor 混加违反不混币种口径);桥断额按币种分别在各 Card 内列示。
- **每币种 Card**:标题 `{currency}` + 一致性 `Tag`(通过=success / 异常=error,沿用 StatusTag 风格);`bridgeBrokenMinor` 非零时 danger 高亮 + 「桥接断裂」文字(复用 `DiscrepancyTypeTag` 对 `BRIDGE_BROKEN` 的 error 判定语义)。
- **两段并列**:`Row gutter={[16,16]}` + 两个 `Col xs={24} md={12}`,各放一段迷你面板(`Descriptions column=1 size=small`:应对/已匹配/缺失/金额差/桥断/守恒);缺段 → `Empty`/`Alert`「链路不完整(缺 SEGx)」。

**冲突裁决(UIUX 子代理倾向"每币种一张 Table 行=SEG1/SEG2" vs 移动端子代理倾向"Row/Col 卡片、避免横滚表")**:
取 **Row/Col 卡片**。理由:三方每币种仅 2 段、字段少,Row/Col 天然 `md 并排 / xs 堆叠`、**零横向滚动**,移动端体验优于横滚 Table;
既有 `screens.md ? Table : mobile-card` 双分支范式是为**列表长表**设计的,用在 2 段小对比上属重武器。金额一律 `formatMinor`(BigInt),迷你面板用 `Descriptions` 均为仓库既有组件。

### D3 · API/类型/缓存接线 —— 纯加法 + 一行失效

- `types.ts` 加 `ThreeWayReport`/`CurrencyRollup`(金额 `string`,`seg1/seg2: ReportEntry|null`,`threeWayBalanced: boolean|null`),`seg` 复用现有 `ReportEntry`。
- `recon.ts` 加 `getThreeWayReport(runId)`,完全沿用 `getRun` 范式(`encodeURIComponent` + `(await api.get<T>()).data`)。
- queryKey `['three-way', runId]`(对齐 `['run-detail',runId]`/`['discrepancy-detail',id]`);`enabled = activeKey==='three-way' && Boolean(runId)`。
- **rerun 后残留旧值风险**:三方从 reports 派生,重跑重算 reports。故在 `RunDetailDrawer` 现有 rerun `onSuccess` 的 `Promise.all` **追加第 5 条** `invalidateQueries(['three-way', runId])`,**不改原 4 条**。这是本区块唯一触及既有行为的点。

### D4 · 移动端 —— **纳入适配,不列 non-goal**

依据:该台虽桌面为主,但既有代码已系统性响应式(抽屉 100%、表格↔卡片切换、44px 触控、`mobile-chromium` e2e 常态跑)。
B1 只做桌面会成全站唯一响应式回退点。成本极低:Row/Col `xs=24 md=12` 自动堆叠、复用既有 44px 触控 CSS、Playwright `mobile-chromium`(Pixel 5, 393px)已存在无需新 project。

### D5 · 被否决/暂不做

- 三方设为默认 Tab —— 破坏 `RunsPage.test`,且原始两段报表作默认更稳妥。
- 跨段求和金额 / 端到端净额 —— 设计口径明令(spine 重复计),仅状态合成。
- 多币种分页 / "只看异常币种"过滤 / running 轮询 —— MVP 币种少、一次性拉取即可;列为未来增强。
- 深链可分享 URL —— 抽屉内视图,现无需求。

## 假设(未确认即标注,不臆造)

1. 三方视图承载于 `RunDetailDrawer` 内 Tab(PROGRESS 允许"抽屉或独立视图",此处倾向抽屉)。
2. `threeWayBalanced=null` 呈现中性态(非红非绿),文案沿用列表页「待生成」;非三方场景与"未出报表"后端均返回 `null+空`,MVP 单场景下不区分成因。
3. 段业务名映射:`SEG1_MKT_ACCT→「SEG1 营销↔账务」`、`SEG2_ACCT_CHANNEL→「SEG2 账务↔渠道」`(前端本地常量)。
4. `bridgeBrokenMinor` 是否"非零"用 `BigInt()` 比较判定,**绝不转 number**。

## 用户已批准的决定(2026-08-19)

- **D1 交互形态 = Tabs(守恒默认)** ✅ 采纳。
- **D6 桥断下钻链接 = 本期加入** ✅ 采纳。落点在 **SegmentPanel(按段精确)**:某段 `bridgeBrokenMinor` 非零 → 链接跳
  `/discrepancies?runId=&segmentId=<该段>&type=BRIDGE_BROKEN`(比按币种总额更准,因桥断有明确 `bridge_break_stage`)。
  **真实成本(诚实标注)**:`DiscrepanciesPage` 现过滤状态是本地 `useState`、**不读 URL**(`router.tsx` 的 `/discrepancies` 无 query 处理)。
  故下钻要真正预置过滤,必须给 `DiscrepanciesPage` 接 `useSearchParams` 在挂载时播种 `filters` + `form`——这是本次新增的一小块面(约 15 行 + 1 测试用例),已纳入 FINAL_PLAN §7/§8/§9。**不做"点了不过滤"的假链接。**
- **D7 banner KPI = 仅计数**(全平/不一致币种数),**禁总桥断额跨币种求和**(见 D2 修订)。

