# B1 三方合并只读视图 · 前端实施计划(FINAL_PLAN)

> 与 `DECISION_RECORD.md` 同目录。经 frontend-plan 五路只读子代理 + 独立评审。**批准前不改任何代码。**
> 数据源:后端已就绪 `GET /recon/runs/{id}/three-way`(只读、`recon.read`)。

## 1. Goals / Non-goals

**Goals**
- 在 `RunDetailDrawer` 内为 `MARKETING_3WAY` 场景的 Run 增加只读「三方合并」视图:整体一致性结论 + 每币种 SEG1/SEG2 两段并列 + 桥断额诊断。
- 金额一律按十进制字符串经 `formatMinor`(BigInt)渲染,**禁转 number**。
- loading/error/空态/缺段/多币种/大额精度全覆盖;桌面 + 移动(Pixel 5 393px)双视口可用。
- 单测 + e2e 冒烟纳入,既有测试保持绿。

**Non-goals**
- 不改后端、不改判差/守恒、不新增 SQL/算法。
- 不跨段求和金额、不做端到端净额(spine 重复计)。
- 不做多币种分页/过滤、不做 running 轮询、不做深链 URL(列为未来增强)。
- 不做 SEG2 发放单跨渠道 roll-up、不做 1:N 明细下钻(设计 Non-goal)。

## 2. 路由与页面流

- **无新路由、无菜单改动**。入口沿用:运行管理(/runs)列表 → 点 Run → `RunDetailDrawer`。
- 抽屉内(仅 `scenarioCode==='MARKETING_3WAY'`):`Tabs` = 「守恒报表」(默认激活,现有表)/「三方合并」(激活才拉)。
- **下钻(本期加入,用户已批准)**:SegmentPanel 中某段 `bridgeBrokenMinor` 非零 → 「查看桥断差异」链接跳
  `/discrepancies?runId=<runId>&segmentId=<该段>&type=BRIDGE_BROKEN`。
  **前置改造**:`DiscrepanciesPage` 现过滤是本地 `useState` 不读 URL,须加 `useSearchParams` 在挂载时把 `runId/segmentId/type/currency/status/q` 播种进 `filters` + `form.setFieldsValue`(否则跳过去过滤不生效)。用 react-router `useNavigate`/`<Link>` 生成链接。

## 3. 组件树(复用现有 vs 新建)

```
RunDetailDrawer.tsx  (MODIFY)
├─ Descriptions「运行信息」                         [复用, 不变]
├─ scenarioCode==='MARKETING_3WAY' ?
│    Tabs activeKey={activeKey} onChange={setActiveKey}  [复用 AntD Tabs, 受控:activeKey+onChange 都必须给]
│    ├─ Tab「守恒报表」(key='conservation', 默认) → conservationReport(现有 Table/Empty, 去掉内层 h3)  [复用]
│    └─ Tab「三方合并」(key='three-way') → <ThreeWayRollupPanel runId enabled={activeKey==='three-way'} />  [新建]
│  : <section><h3 className="section-title">守恒报表</h3>{conservationReport}</section>   [复用, 现状]
└─ rerun.onSuccess: Promise.all[...原4条, 追加 invalidate(['three-way',runId])]         [MODIFY 1 行]
   // ⚠️ 阻断项①: 受控 Tabs 必须同时给 activeKey + onChange={setActiveKey};
   //    只给 activeKey 会切不动 → enabled 永 false → 面板永不加载、e2e 必挂。初值 'conservation'。

ThreeWayRollupPanel.tsx  (NEW, src/components/runs/)
├─ const q = useQuery(['three-way', runId], () => getThreeWayReport(runId), { enabled: enabled && Boolean(runId) })
├─ if (q.isLoading) → <PageSkeleton/>            [复用 AsyncState] (enabled=false 时 isLoading=false, 不误显骨架)
├─ if (q.isError)   → <ErrorState onRetry={q.refetch}/>  [复用 AsyncState]
├─ if (!q.data || q.data.currencies.length===0 || q.data.threeWayBalanced==null)
│      → <Empty description="该 Run 无三方链路报表"/>  [复用]
│    // ⚠️ 阻断项③: !q.data 卫语句必须在 data.currencies 解引用之前, 防 forceRender/eager 改动后裸解引用崩溃
└─ 正常 →
   ├─ ThreeWayBalanceBanner(内联): threeWayBalanced 三态 + 图标 + Tag + KPI「不一致币种数」(计数, 非跨币种金额求和)
   └─ q.data.currencies.map → <CurrencyRollupCard key={c.currency} rollup={c} />

CurrencyRollupCard.tsx  (NEW, src/components/runs/)
├─ Card title={currency} + <Tag color={rollup.threeWayConsistent?'success':'error'}>{通过|异常}</Tag>
│    // 一致性直接用后端 rollup.threeWayConsistent, 勿从 seg.balanced 重算(缺段会空指针)
├─ extra: isNonZeroMinor(rollup.bridgeBrokenMinor) → <Text type=danger>桥接断裂 {formatMinor(v,currency)}</Text>
│    // isNonZeroMinor() = 带 try/catch 的 BigInt(v)!==0n, 与 formatMinor 一致防御, 禁转 number
└─ Row gutter=[16,16]
   ├─ Col xs=24 md=12 → <SegmentPanel label="SEG1 营销↔账务" seg={seg1} currency/>  (seg1==null → Empty「链路不完整(缺 SEG1)」)
   └─ Col xs=24 md=12 → <SegmentPanel label="SEG2 账务↔渠道" seg={seg2} currency/>

SegmentPanel(可内联于 CurrencyRollupCard 或同文件小组件)  props: { label, seg: ReportEntry|null, currency, runId }
├─ seg==null → <Empty/Alert「链路不完整(缺该段)」>
├─ Descriptions column=1 size=small bordered: 应对/已匹配/缺失/金额差/桥断(formatMinor) + 守恒(<Text success|danger>通过|异常</Text>)
└─ isNonZeroMinor(seg.bridgeBrokenMinor) → <Link to={`/discrepancies?runId=${enc(runId)}&segmentId=${seg.segmentId}&type=BRIDGE_BROKEN`}>查看桥断差异</Link>
```

**下钻前置**:`DiscrepanciesPage.tsx`(MODIFY)加 `const [sp]=useSearchParams()`;`useState` 初值改为从 `sp` 读取
`{runId,segmentId,type,currency,status,q,page:0,size:20}`,并 `form` 初值同源(`initialValues` 或挂载 `form.setFieldsValue`)。
仅当有 query 时预置,无 query 时行为不变。

**新建文件**:`ThreeWayRollupPanel.tsx`、`CurrencyRollupCard.tsx`(SegmentPanel 与 Banner 先内联,复用/测试需要再拆)。

## 4. 状态与边界(逐态)

| 态 | 触发 | 呈现 |
|---|---|---|
| loading | 三方 Tab 激活、请求中 | `PageSkeleton`(`getByLabelText('正在加载')`) |
| error | 请求失败(如 404/500/网络) | `ErrorState`(「页面加载失败」+ 重试 → refetch) |
| 空/不适用 | `currencies=[]` 或 `threeWayBalanced==null` | `Empty`「该 Run 无三方链路报表」(**不渲染成绿色通过**) |
| 全平 | `threeWayBalanced===true` | banner success「三方守恒 · 全平」+ `CheckCircleOutlined` |
| 不一致 | `threeWayBalanced===false` | banner danger「三方不一致」+ `CloseCircleOutlined`(需新 import,包内存在);不一致币种 Card 红标;KPI 显「不一致币种数」(计数,**非**跨币种金额求和) |
| 段配上但不平 | `seg1!=null && seg2!=null && (!seg1.balanced || !seg2.balanced)` | 两段都展示,`threeWayConsistent=false`,守恒列该段显「异常」(区别于缺段) |
| 缺段 | `seg1==null` 或 `seg2==null` | 该段 Col 显 `Empty`/`Alert`「链路不完整(缺 SEGx)」,另一段正常展示;该币种一致性必为异常 |
| 桥断 | `bridgeBrokenMinor` 非零(BigInt≠0) | Card extra danger 高亮 + 「桥接断裂」文字 |
| 多币种 | `currencies.length>1` | 逐 Card 渲染,`key=currency`(禁数组下标) |
| 大额 | 金额 > 2^53 或 24 位串 | `formatMinor`(BigInt)千分位,**无科学计数、无截断** |

## 5. API 契约(前端侧新增)

```ts
// src/api/types.ts (追加)
export interface CurrencyRollup {
  currency: string
  seg1: ReportEntry | null
  seg2: ReportEntry | null
  threeWayConsistent: boolean
  bridgeBrokenMinor: string   // 十进制字符串
}
export interface ThreeWayReport {
  runId: string
  scenarioCode: string
  accountingPeriod: string
  status: string
  threeWayBalanced: boolean | null
  currencies: CurrencyRollup[]
}

// src/api/recon.ts (追加)
export async function getThreeWayReport(runId: string): Promise<ThreeWayReport> {
  return (await api.get<ThreeWayReport>(`/recon/runs/${encodeURIComponent(runId)}/three-way`)).data
}
```

- 请求头/鉴权:沿用 `client.ts` 拦截器,无需改动。
- 权限:`recon.read`,已由路由守卫覆盖,面板内**不需 `can()`**。

## 6. 响应式与移动端策略

- **断点**:沿用 antd 默认 `Grid.useBreakpoint()`;两段用 `Col xs={24} md={12}`(md 并排 / 手机堆叠),`gutter={[16,16]}`。
- **无横向滚动**:不使用 `scroll={{x}}` 大表;迷你面板 `Descriptions column=1` 天然纵向,窄屏可读。
- **banner**:用 `Descriptions column={{ xs:1, md:2 }}`(AntD 响应式对象写法,**无 `screens.md` JS 三元**)——DOM 恒含全部 Item、jsdom 可测、不因分支崩;缺段/桥断信息平铺其中。
- **触控/字号**:面板内可点元素为 Tab 标签与 `ErrorState` 重试按钮;既有 `.page-header-actions .ant-btn{min-height:44px}` 选择器**不覆盖**本面板,故本期不宣称复用该 CSS;正文沿用卡片内 13–14px。下钻链接是 Non-goal,面板本身可点交互极少。
- **验收视口**:Pixel 5(393px)——两段纵向堆叠、无页面横向滚动、一致性 Tag 与桥断文字可读。

## 7. 文件级改动清单

| 文件 | 改动 | 类型 |
|---|---|---|
| `src/api/types.ts` | 加 `ThreeWayReport`/`CurrencyRollup` interface | 纯加法 |
| `src/api/recon.ts` | 加 `getThreeWayReport(runId)` | 纯加法 |
| `src/components/runs/ThreeWayRollupPanel.tsx` | 新建容器(query + 三态 + banner + 币种列表) | 新建 |
| `src/components/runs/CurrencyRollupCard.tsx` | 新建每币种卡(含 SegmentPanel) | 新建 |
| `src/components/runs/RunDetailDrawer.tsx` | 抽屉 body 条件 Tabs;守恒表去内层 h3 移入 Tab label;rerun.onSuccess 追加第 5 条失效;新增 `activeKey` state | 修改 |
| `src/pages/DiscrepanciesPage.tsx` | 加 `useSearchParams`,挂载时从 URL query 播种 `filters` + `form`(支撑桥断下钻预置过滤) | 修改 |
| `src/components/runs/ThreeWayRollupPanel.test.tsx` | 新建单测(见 §9) | 新建 |
| `src/pages/RunsPage.test.tsx` | `vi.mock('../api/recon')` 工厂**补一行** `getThreeWayReport: vi.fn()`(防御性,与既有 4 个并列;把测试与 AntD 懒挂载实现细节解耦) | 修改(1 行) |
| `e2e/console.smoke.spec.ts` | `mockApi` 加 `/three-way` 路由 + **非空 currencies** fixture;加「点开 Run 抽屉 → 点三方合并 Tab → 断言」步骤 | 修改 |

**不改**:`router.tsx`、`AppLayout.tsx`、`client.ts`、`RunsPage.tsx`。
`RunsPage.test.tsx` 的断言逻辑不变、仅工厂补一个 `vi.fn()`(评审确认三方懒加载对现断言零影响,补 mock 是加固而非必须;交付时跑一遍确认绿)。

## 8. 实施步骤(按依赖排序)

1. **类型**:`types.ts` 加两个 interface。
2. **API**:`recon.ts` 加 `getThreeWayReport`。
3. **展示叶子**:`CurrencyRollupCard.tsx`(+ 内联 SegmentPanel + 段名常量),纯 props 渲染,金额走 `formatMinor`,桥断用 BigInt 判非零。
4. **容器**:`ThreeWayRollupPanel.tsx`(query + 三态 + 内联 banner + map 币种)。
5. **接线抽屉**:`RunDetailDrawer.tsx` 引入 `const [activeKey,setActiveKey]=useState('conservation')` + 条件 Tabs(`activeKey` **和** `onChange={setActiveKey}` 都给)+ 挂 Panel(`enabled={activeKey==='three-way'}`)+ rerun 第 5 条失效;把守恒表抽为 `conservationReport` 变量供两分支复用,去掉 Tab 分支内的重复 h3。
6. **下钻接线**:`DiscrepanciesPage.tsx` 加 `useSearchParams` 播种 `filters`+`form`;SegmentPanel 加 `<Link>`(桥断非零时)。
7. **单测**:`ThreeWayRollupPanel.test.tsx`(§9 用例)+ `DiscrepanciesPage` 的 URL 预置过滤用例。
8. **e2e**:`console.smoke.spec.ts` 加 mock 路由 + 断言(可含点下钻链接后落到差异页且过滤生效)。
9. **回归**:`pnpm test`(含 RunsPage.test 确认绿)、`pnpm build`(tsc)、`pnpm e2e`(desktop+mobile 双 project)。

## 9. 测试策略

**单测**(`ThreeWayRollupPanel.test.tsx`,照抄 `DiscrepancyDetailDrawer.test.tsx` 范式:`vi.mock('../../api/recon')` 枚举 SUT 实际 import 的导出 + `vi.mocked().mockResolvedValue` + `renderApp`):
- `threeWayBalanced` = true / false / null 三态(null → Empty,不渲染红异常、不崩)。
- 缺段:`seg1=null&&seg2≠null`、`seg1≠null&&seg2=null`、双 null;断言占位「链路不完整」且不抛错。
- 多币种:currencies 含 USD/EUR/JPY,`key=currency`,各行带各自币种前缀。
- **大额精度(重点)**:真实边界用 `bridgeBrokenMinor='9007199254740993'`(>2^53,后端 Long 可达);另加一个纯合成 24 位串仅作 BigInt 路径压力(注:后端 Long 最多 19 位,24 位非真实后端场景);正向断言千分位文本、反向断言 `queryByText(/e\+/i)` 为 null 且无 `...740992` 截断;JPY(无小数)、负额 `'-100'` 各一例。
- loading(`PageSkeleton`)/ error(`mockRejectedValueOnce(new ApiError(...))` → `ErrorState`,点重试再次调用)。
- `enabled=false`(runId=null 或 tab 未激活)→ `waitFor` 断言 `getThreeWayReport` 未被调用。
- jsdom 陷阱:`setup.ts` 令 `matchMedia.matches=false` → `screens.md` 恒 falsy;Row/Col 无双分支渲染,故默认覆盖即可;banner 若含 `screens.md` 分支,需要断言桌面态时临时覆写 `window.matchMedia`。

**e2e**(`console.smoke.spec.ts`,已双 project desktop+mobile):
1. `mockApi` 加 `if pathname===/recon/runs/${enc(runId)}/three-way → fulfill(threeWayFixture)`,fixture 的 `currencies` **必须非空**且 `threeWayBalanced` 非 null(否则落 Empty,断言不到「三方守恒」)。
2. 现 smoke 从未点开 Run 抽屉、`getRun` 路由是死代码;新步骤须显式:进 /runs → `click(runId)` 开抽屉 → 点「三方合并」Tab → 断言关键文本(「三方守恒」/币种/桥断)可见。
3. `navigateByMenu` 已处理移动端 <992 汉堡菜单,`desktop-chromium` + `mobile-chromium`(Pixel 5)两 project 自动覆盖,无需改 `playwright.config.ts`。

## 10. 验收标准

- [ ] `MARKETING_3WAY` Run 抽屉出现「守恒报表 / 三方合并」双 Tab,守恒默认激活;点开三方才发 `getThreeWayReport`。
- [ ] 三态 banner 正确(true 绿通过 / false 红不一致 / null 中性待生成),每态有文字+图标(非仅颜色)。
- [ ] 每币种展示 SEG1/SEG2 两段金额 + 一致性 Tag + 桥断额;缺段显「链路不完整」不崩。
- [ ] 段桥断非零时出「查看桥断差异」链接;点击落到 `/discrepancies` 且 `runId+segmentId+type=BRIDGE_BROKEN` **过滤已预置生效**(非仅跳转)。
- [ ] 大额(24 位)按字符串正确显示,无科学计数、无精度截断。
- [ ] 重跑后三方 roll-up 同步刷新(`['three-way',runId]` 被失效)。
- [ ] **移动端(Pixel 5 393px)**:两段纵向堆叠、无页面横向滚动、Tag 与桥断文字可读。
- [ ] `pnpm test` 全绿(含既有 `RunsPage.test`)、`pnpm build`(tsc)通过、`pnpm e2e` 双 project 通过。

## 11. 风险与回滚

- **风险(低)**:纯新增只读区块;唯一触及既有行为=rerun.onSuccess 追加一行失效(风险极低)。金额转 number 隐患由"全程 `formatMinor`/BigInt + 24 位串测试"守住;null 段空指针由缺段用例守住;懒加载确保未开 Tab 不发请求。
- **回滚**:删除两个新组件 + 抽屉内 Tab 引用与第 5 条失效即回到现状;无 DB/迁移/feature flag/跨模块副作用。
