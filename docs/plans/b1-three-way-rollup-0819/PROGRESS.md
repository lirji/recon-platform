# B1 三方合并只读视图 · 实施进度

> 路线图 `docs/PHASE2_ROADMAP.md` Track B · B1(P1 速赢 · 工作量 S–M)。**后端只读 API + 前端页均已完成 2026-08-19。**
> 前端经 frontend-plan 全流程(五路只读子代理 → 决策记录 → 实施计划 → 独立评审 → 用户批准 → 实现),
> 计划与决策记录见 `docs/plans/b1-three-way-frontend-0819-1109/`。

## 背景与口径来源

设计定稿口径决议 **A2** 只声明「MVP 只出两段独立报表;三方合并 roll-up 视图归阶段二」,**未定义合并细则**。
按 CLAUDE.md「缺失需求不臆造——标为假设/约定」,此处为阶段二取定一个**保守、无算法风险**的合成口径并显式标注(见下)。

## 已完成 ✅ — 后端只读 API

| 交付 | 文件 | 说明 |
|---|---|---|
| DTO | `service/ReconConsoleQueryRepository.java` | `ThreeWayReport` / `CurrencyRollup`(金额十进制字符串,防 BIGINT 精度损失) |
| 派生逻辑 | `service/ReconConsoleQueryService.threeWayRollup(runId)` | 从既有 `RunDetail` 的两段报表派生,**不引入新 SQL/算法** |
| 端点 | `web/ReconConsoleController` | `GET /recon/runs/{id}/three-way`(GET 落 `/recon/**`→`recon.read`,无需改安全配置) |
| 测试 | `web/ThreeWayRollupTest`(新) | 3 用例:两段皆平→balanced、桥断+不平→不一致且桥断求和、缺段→不一致 |

## 合成口径(阶段二取定,显式约定)

- 按币种分组,每币种取 SEG1(营销↔账务)/ SEG2(账务↔渠道)原始报表;缺段 → 该币种链路不完整。
- **`threeWayConsistent` = 两段均在且均 balanced(布尔与)**。关键决定:**不跨段求和金额** —— spine(账务)被两段共享,
  相加会重复计账务侧;故只做**状态合成**,原始各段金额并列呈现供下钻(前端可展开两段明细)。
- **`bridgeBrokenMinor` = 两段桥断额之和**(SEG1/SEG2 是两个独立断点阶段,金额独立,非重复计),三方链路专有诊断;
  `Math.addExact` 溢出 fail-fast(与 MoneyMath 一致)。
- **`threeWayBalanced` = 所有币种皆 consistent**(无报表 → null)。
- 仅识别营销三方场景两段(`MarketingThreeWayScenario.SEG1/SEG2`;MVP 唯一三方场景),其它段忽略。

## 验证证据(2026-08-19)

- `ThreeWayRollupTest`(@SpringBootTest+MockMvc,H2 seed)**3/3**。
- `ReconConsoleControllerTest` 回归 **4/4**(既有只读接口无影响)。

## 已完成 ✅ — 前端页(2026-08-19)

`RunDetailDrawer` 内新增 Tabs:「守恒报表」(默认)/「三方合并」(懒加载,仅 `MARKETING_3WAY` 场景显示)。
三方 Tab = 三态一致性 banner(全平/不一致/无报表)+ 每币种 Card(SEG1/SEG2 两段 `Row/Col` 并列、
一致性 Tag、桥断额)。金额全程 `formatMinor`(BigInt,禁转 number)。**桥断下钻**:段桥断非零 → 链接跳
`/discrepancies?runId&segmentId&type=BRIDGE_BROKEN`(为此给 `DiscrepanciesPage` 接 `useSearchParams` 播种过滤)。

**改动文件**:`api/{types,recon}.ts`、`components/runs/{ThreeWayRollupPanel,CurrencyRollupCard}.tsx`(新)、
`components/runs/RunDetailDrawer.tsx`(Tabs + rerun 追加 `['three-way']` 失效)、`pages/DiscrepanciesPage.tsx`(URL 播种)、
`utils/format.ts`(`isNonZeroMinor`)、`test/render.tsx`(`initialEntries`)、`test/setup.ts`(全局 cleanup)。
**测试**:`ThreeWayRollupPanel.test.tsx`(9)+ `DiscrepanciesPage.test.tsx`(2)+ e2e 三方冒烟(desktop+mobile)。

**验证证据**:`pnpm test` 16/16、`pnpm build`(tsc+vite)通过、`pnpm e2e` 4/4(双 project)。

### 合成口径落地校验
- **不跨段/不混币种求和**:banner KPI 只用「不一致币种数」计数;桥断额按币种/按段分别列示,无跨币种相加。
- **缺段**:seg=null → 「链路不完整(缺 SEGx)」,不空指针。
- **大额精度**:24 位 / >2^53 串测试断言无科学计数、无 number 截断。

## 诚实边界

- 只做了后端派生 + 契约测试;真实两段报表由完整对账 Job 产出,本 API 只读投影,不改判差/守恒。
- 合成口径的「不跨段求和」是为避免 spine 重复计;若产品要「端到端净额」等更强口径,需另定并评审(高风险,勿臆造)。
