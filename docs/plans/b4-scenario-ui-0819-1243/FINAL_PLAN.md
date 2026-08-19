# B4 · 场景管理前端页 · 实施计划(FINAL_PLAN)

> 与 `DECISION_RECORD.md` 同目录。经 frontend-plan 五路子代理 + 独立评审。**批准前不改任何代码。**
> 数据源:后端 `ScenarioAdminController` 已就绪(list/get/put)。

## 1. Goals / Non-goals

**Goals**
- recon-console 新增「场景管理」页:列表查看场景定义、查看某场景详情(完整 JSON)、新建/编辑场景定义、启停用。
- 写操作 `can('recon.launch')` 门控(隐藏而非禁用);读所有登录用户可见。
- JSON 编辑用 `Input.TextArea`,**提交原始文本**避免金额精度损失;`JSON.parse` 仅做语法校验 + 读 code。
- 桌面 + 移动(Pixel 5 393px)双视口可用;单测 + e2e 冒烟纳入;既有测试保持绿。

**Non-goals**
- 不引入 monaco/codemirror(用 `Input.TextArea`)。
- 不做逐字段结构化表单/可视化拖拽构建器(JSON 编辑够用)。
- 不做删除(后端无 delete)、不做 409 乐观锁(后端无 expectedVersion)、列表不分页(裸数组)。
- 不做列表行内 enabled 即时开关(改在编辑抽屉内)。
- 本期不改 RunsPage/LaunchRunModal 的 scenarioCode 下拉(硬编码 MARKETING_3WAY 保持;闭环列后续)。

## 2. 路由与页面流

- **新路由** `/scenarios`(lazy,挂 `RequireAuth recon.read` 下,无额外 launch 守卫);**菜单**加「场景管理」(`SettingOutlined`)。
- 流程:菜单进 `/scenarios` → 列表(`GET /recon/scenarios`)→ 点行打开编辑抽屉(`GET /recon/scenarios/{code}` 回显)→ 改 JSON/enabled → 保存(`PUT`)→ 成功失效列表刷新。
- 新建:PageHeader「新建场景」(门控)→ 抽屉新建模式(code 可编辑 + 模板骨架)→ 保存(判重 Popconfirm)。

## 3. 组件树(复用现有 vs 新建)

```
ScenariosPage.tsx  (NEW, src/pages/)
├─ PageHeader(eyebrow "SCENARIO CATALOG", title "场景管理", extra=can('recon.launch') ? <Button 新建场景 primary PlusOutlined> : undefined)  [复用 PageHeader]
├─ useQuery(['scenarios'], listScenarios)
├─ isError → <ErrorState onRetry=refetch/>                     [复用 AsyncState]
├─ screens.md ? <Table> : <mobile-data-card 列表>              [复用 RunsPage 双分支范式]
│    列: code(.mono) | 段数(segmentCount) | 启用(<ScenarioEnabledTag>) | 版本(version) | 操作(编辑, fixed:right)
│    empty → locale.emptyText=<EmptyState/>
├─ const [editing, setEditing] = useState<{mode:'new'|'edit'; code: string|null} | null>(null)
└─ <ScenarioEditorDrawer editing={editing} existingCodes={(data??[]).map(s=>s.code)} onClose={()=>setEditing(null)} />
     // M3: 把已加载列表的 code 传进抽屉供新建判重

ScenarioEnabledTag.tsx  (NEW, src/components/scenarios/ 或内联)  [仿 StatusTag: Tag color + 中文]
   enabled ? <Tag success 启用> : <Tag default 停用>

ScenarioEditorDrawer.tsx  (NEW, src/components/scenarios/)
   props: { editing: {mode:'new'|'edit'; code: string|null} | null, existingCodes: string[], onClose }
├─ Drawer open={Boolean(editing)} destroyOnHidden width={screens.md?900:'100%'} title extra=保存按钮(can('recon.launch') 门控, loading=mutation.isPending)
│    // ⚠️ M2: open 用独立 editing 标志(新建模式无 code, 不能 open={Boolean(code)});destroyOnHidden 保证每次打开内容重挂→useState 重init, 无残留串场景
├─ 编辑模式: useQuery(['scenario-detail', editing.code], getScenario, enabled: editing?.mode==='edit' && Boolean(editing?.code))
│    isPending→<PageSkeleton/> / isError→<ErrorState/> / data→播种
├─ 本地 state: jsonText:string, enabled:boolean, parseError, saveError
├─ 播种(⚠️ M1+M2):useEffect 依赖 [editing?.mode, editing?.code, detail.data];用 ref 记 lastSeededKey, 仅当 key(mode:code)变化才 seed:
│    · 编辑: jsonText=JSON.stringify(detail.definition,null,2); **enabled=detail.enabled**(必须从 detail 播种, 否则改 JSON 不碰开关会静默翻 enabled)
│    · 新建: jsonText=模板骨架; enabled=true(显式初值); code 由输入
│    · ref-guard 防后台 refetch 重新 seed 覆盖用户编辑
├─ Form.Item code (新建 Input+pattern / 编辑 disabled 只读)
├─ Form.Item「定义(JSON)」 <Input.TextArea className="mono" autoSize={{minRows:10,maxRows:24}} value=jsonText onChange=.../>
│    + parseError 内联报红;viewer(无 recon.launch)可加 readOnly
├─ Form.Item enabled <Switch checkedChildren=启用 unCheckedChildren=停用 checked=enabled/>
├─ saveError → 顶部 <Alert type=error showIcon>(后端 400 语义错误回显)
└─ onSave: 见 §5 提交流程(含 M3 判重命令式确认)
```

**新建文件**:`src/pages/ScenariosPage.tsx`、`src/components/scenarios/ScenarioEditorDrawer.tsx`、`src/components/scenarios/ScenarioEnabledTag.tsx`(独立小文件,便于列表+抽屉复用)。

## 4. 状态与边界(逐态)

| 态 | 触发 | 呈现 |
|---|---|---|
| 列表 loading | 请求中 | Table `loading` / 首载 `PageSkeleton` |
| 列表 error | 失败 | `ErrorState` + 重试 |
| 列表 empty | 空数组 | `EmptyState`「还没有可展示的数据」 |
| 抽屉 loading | 编辑模式 GET 中 | `PageSkeleton` |
| 抽屉 error | GET 失败 | `ErrorState` |
| JSON 语法错 | 保存前 `JSON.parse` 抛 | 内联报红,**saveScenario 不调用** |
| code 不符(新建) | body.code≠path/输入 code | 客户端预检报错 或 后端 400 回显 |
| 保存 400 | 后端装配校验失败 | 顶部 `Alert`(`errorMessage`)+ `message.error`;抽屉不关、列表不变 |
| 保存成功 | PUT 2xx | `message.success('场景已保存')` + 失效 `['scenarios']`+`['scenario-detail',code]` + 关抽屉 |
| 新建判重 | code 已在列表 | `Popconfirm`「已存在,将覆盖?」 |
| 无 recon.launch | viewer | 新建/保存按钮**隐藏**;仍可读列表/详情 |
| enabled 缺省陷阱 | 保存 | 恒显式传当前 enabled(不依赖后端默认 true) |

## 5. API 契约(前端侧新增)

```ts
// src/api/types.ts (追加)
export type SourceRole = 'MARKETING' | 'ACCOUNTING' | 'CHANNEL'
export type EvaluatorType = 'EXACT' | 'TOLERANCE' | 'DROOLS'
export type DiscrepancyKind =
  | 'BRIDGE_BROKEN' | 'CURRENCY_MISMATCH' | 'DUPLICATE' | 'EXTRA' | 'GROUP_SUM_MISMATCH'
  | 'AMOUNT_MISMATCH' | 'STATUS_MISMATCH' | 'TIMING' | 'MISSING' | 'FX_RATE_DIFF'
export interface ScenarioSource { sourceType: string; params: Record<string, string> }
export interface ScenarioRule { evaluatorType: EvaluatorType; absToleranceMinor: number; ratioToleranceBps: number; enabledTypes: DiscrepancyKind[] | null }
export interface ScenarioSegment { id: string; leftRole: SourceRole; rightRole: SourceRole; spineRole: SourceRole | null; stageLabel: string; matchKeyField: string; groupKeyField: string; left: ScenarioSource; right: ScenarioSource; rule: ScenarioRule }
export interface ScenarioDefinition { code: string; segments: ScenarioSegment[] }
export interface ScenarioSummary { code: string; version: number; enabled: boolean; segmentCount: number }
export interface ScenarioView { code: string; version: number; enabled: boolean; definition: ScenarioDefinition }

// src/api/recon.ts (追加)
export async function listScenarios(): Promise<ScenarioSummary[]> {
  return (await api.get<ScenarioSummary[]>('/recon/scenarios')).data   // 裸数组, 勿套 PageResult
}
export async function getScenario(code: string): Promise<ScenarioView> {
  return (await api.get<ScenarioView>(`/recon/scenarios/${encodeURIComponent(code)}`)).data
}
// 关键: 发原始 JSON 文本 body, 避免 JSON.parse→number→stringify 丢精度; axios 对 string body 不再 stringify。
export async function saveScenario(code: string, definitionJson: string, enabled: boolean): Promise<ScenarioView> {
  return (await api.put<ScenarioView>(`/recon/scenarios/${encodeURIComponent(code)}`, definitionJson, {
    params: { enabled }, headers: { 'Content-Type': 'application/json' },
  })).data
}
```

**提交流程(saveScenario 调用前,抽屉内)**:
1. `try { parsed = JSON.parse(jsonText) } catch { setParseError('JSON 语法错误: '+e.message); return }`(不提交)。
   ⚠️ 这步不只是 UX:若非法 JSON 漏进 axios,其 `stringifySafely` 会命中 SyntaxError 分支把整段**双重编码**成带引号字符串污染 body。故此校验是正确性必需(注释里点明,勿当冗余删)。
2. 客户端预检 `parsed.code === code`(编辑模式 code 固定;新建模式 code=输入值)。不符 → 内联报错(fast-fail,真正边界仍是后端 400)。
3. **M3 判重(仅新建模式)**:`if (mode==='new' && existingCodes.includes(code))` → 命令式 `Modal.confirm({title:'场景已存在,将覆盖?', onOk: doSave})`;否则直接 `doSave()`。(声明式 Popconfirm 无法干净表达「仅重复才确认」,改用 `Modal.confirm`。)
4. `doSave = () => mutation.mutate()` → `saveScenario(code, jsonText, enabled)` —— **发 jsonText 原文**(axios 对合法 JSON string body 原样透传,不 re-stringify,提交侧不丢精度)。
5. onSuccess/onError 见 §4。

**精度诚实边界(M4)**:提交侧原文本透传对现实取值安全;但**读侧有损** —— `getScenario` 走 axios 默认 `transformResponse`(`JSON.parse` 响应体),`>2^53` 的 `absToleranceMinor` 回显前已被舍入,编辑含此大值的既有场景会写坏。**取舍**:容差现实远小于 2^53,本期**不**引入 bigint-safe `transformResponse`(范围外),精度目标限定「提交侧 + 现实小额」,读侧大 long 有损为**已记录已知限制**;不谎称「零精度损失」。另注:`absToleranceMinor` 是后端 `long`,超 `Long.MAX`(19 位)的串 Jackson 直接 400,故测试/验收用 `9007199254740993`(16 位,>2^53 且 ≤Long.MAX),**不用 24 位**。

queryKey:`['scenarios']`、`['scenario-detail', code]`;保存 onSuccess `Promise.all([invalidate(['scenarios']), invalidate(['scenario-detail', code])])`。**不**连带失效 runs/dashboard。

## 6. 响应式与移动端策略

- **断点**:沿用 antd 默认;列表 `screens.md ? <Table scroll={{x:900}}> : <mobile-data-card 列表>`;抽屉 `width={screens.md?900:'100%'}`。
- **JSON 编辑**:`Input.TextArea` `mono` + `autoSize`;小屏可读可编辑(复杂编辑引导桌面)。
- **触控**:抽屉 footer 保存按钮小屏给 `min-height:44px`(复用/新增少量 CSS);enabled `Switch` 保持。
- **已知空白(记录不静默)**:仓库无 safe-area/键盘避让;小屏全宽抽屉 + 底部保存按钮可能被软键盘顶起——本期不引入 `viewport-fit=cover`,保存按钮置于抽屉 `extra`(顶部)规避底部遮挡。
- **验收视口**:Pixel 5(393px)——列表卡片堆叠无横向滚动、抽屉全宽、enabled Tag/保存按钮可读可点。

## 7. 文件级改动清单

| 文件 | 改动 | 类型 |
|---|---|---|
| `src/api/types.ts` | 加 Scenario* 类型 + 枚举联合 | 纯加法 |
| `src/api/recon.ts` | 加 listScenarios/getScenario/saveScenario(原文本 PUT) | 纯加法 |
| `src/pages/ScenariosPage.tsx` | 新建列表页 | 新建 |
| `src/components/scenarios/ScenarioEditorDrawer.tsx` | 新建编辑抽屉(query+三态+JSON+enabled+提交流程+判重) | 新建 |
| `src/components/scenarios/ScenarioEnabledTag.tsx` | 新建 enabled 状态标签(仿 StatusTag) | 新建 |
| `src/router.tsx` | 加 `/scenarios` lazy 路由 | 修改(加法) |
| `src/components/layout/AppLayout.tsx` | navigation 加「场景管理」+ import SettingOutlined | 修改(加法) |
| `src/pages/ScenariosPage.test.tsx` | 新建单测 | 新建 |
| `src/components/scenarios/ScenarioEditorDrawer.test.tsx` | 新建单测 | 新建 |
| `e2e/console.smoke.spec.ts` | mockApi 加 /recon/scenarios 路由 + 场景页冒烟 | 修改 |
| `src/styles/global.css` | (可选)抽屉保存按钮小屏 44px | 修改(少量) |

**不改**:runs/discrepancies 现有页与测试;client.ts;auth。

## 8. 实施步骤(按依赖排序)

1. **类型**:types.ts 加 Scenario* 与枚举联合。
2. **API**:recon.ts 加三函数(saveScenario 原文本 PUT)。
3. **叶子**:`ScenarioEnabledTag`(Tag+文字)。
4. **抽屉**:`ScenarioEditorDrawer`(query + 三态 + JSON TextArea + enabled Switch + 提交流程 + 错误回显 + 门控)。
5. **列表页**:`ScenariosPage`(query + Table/移动卡 + 新建按钮门控 + 打开抽屉)。
6. **接线**:router.tsx 加路由;AppLayout 加菜单项。
7. **单测**:ScenariosPage.test + ScenarioEditorDrawer.test(§9)。
8. **e2e**:console.smoke.spec.ts 加 mock 路由 + 场景页步骤。
9. **回归**:`pnpm test`(含既有)、`pnpm build`(tsc)、`pnpm e2e`(双 project)。

## 9. 测试策略

**单测**(照抄 `DiscrepancyDetailDrawer.test.tsx`:`vi.mock('../api/recon')` + `vi.mocked().mockResolvedValue` + `mockRejectedValueOnce(new ApiError(msg,400,'bad_request'))`;权限用 `mockAuth({permissions:['recon.read']})`;jsdom `matchMedia.matches:false` → 默认移动分支,列表断言对准 `mobile-data-card`):
- 列表渲染:启用/停用各一,断言 code、segmentCount、enabled Tag 文字。
- 权限门控:viewer(仅 recon.read)看不到「新建场景」「保存」;有 recon.launch 则可见。
- 编辑抽屉:点行 → `getScenario` 被调 → TextArea 回显 `JSON.stringify(definition,null,2)`(`toHaveValue`)。
- 保存成功:`saveScenario` 以 `(code, <原文本>, enabled)` 被调;`message.success`;列表失效 refetch。
- 保存 400:`mockRejectedValueOnce(ApiError('segment SEG1 invalid',400))` → `findByText('segment SEG1 invalid')`,抽屉不关。
- JSON 非法:填坏 JSON 点保存 → 内联报错,**断言 `saveScenario` 未被调用**。
- enabled 显式(切换):切换开关后保存,断言传的是切换后的 enabled。
- **enabled 播种(M1,更关键)**:打开 `enabled:true` 的场景,**不动开关**、只改 JSON 后保存 → 断言 `saveScenario` 收到 `enabled===true`(锁「不碰开关则从 detail 播种、不被 useState 初值静默翻」)。
- **state 不串场景(M2)**:先开场景 A 编辑 jsonText,关闭,再开场景 B → 断言 TextArea 是 B 的定义(destroyOnHidden + 按 key 重挂,无残留)。
- **新建判重(M3)**:新建模式输入已存在的 code 保存 → 断言弹出 `Modal.confirm`(覆盖确认);输入新 code 则直接保存。
- **精度(提交侧,单测)**:jsonText 含 `absToleranceMinor: 9007199254740993`(16 位),断言 `saveScenario` 收到的第 2 参**原文本仍逐字含该串**(证明抽屉→saveScenario 传的是 jsonText 原文,不经 number 化)。

**e2e**(`console.smoke.spec.ts`,双 project desktop+mobile):`mockApi` 加 GET `/recon/scenarios`、GET/PUT `/recon/scenarios/MARKETING_3WAY`(路由分支放在 `return 404` 之前);`navigateByMenu(page,'场景管理')` → 断言列表可见 → 点开编辑抽屉 → 断言 JSON 回显 → 改 enabled/JSON 保存 → 断言 toast。
- **原文本透传的唯一端到端证据(M4)**:PUT 的 mock handler 内断言 `route.request().postData()` **逐字包含**大数串 `9007199254740993`(证明 axios+网络层原样透传,而非单测里 mock 掉 saveScenario 的「面子」断言)。desktop 覆盖 Table,mobile 覆盖卡片。

## 10. 验收标准

- [ ] 菜单出现「场景管理」,`/scenarios` 列表渲染 code/段数/启用/版本/操作。
- [ ] 点行打开抽屉,完整 definition JSON 回显可编辑。
- [ ] 保存:成功 toast + 列表刷新;后端 400 顶部 Alert 回显、抽屉不关。
- [ ] JSON 语法错前端拦截(不发请求);大额 `absToleranceMinor`(16 位 `9007199254740993`,>2^53 且 ≤Long.MAX)**提交侧**原文本透传(e2e postData 逐字含该串)。读侧大 long 有损为已记录限制。
- [ ] 编辑既有场景不动 enabled 开关保存,enabled 不被静默翻转;切换不同场景无 state 残留。
- [ ] enabled 在抽屉内可改,保存恒显式带当前值。
- [ ] viewer(仅 recon.read)看不到写控件,仍可读列表/详情。
- [ ] **移动端(393px)**:列表卡片堆叠无横向滚动、抽屉全宽、保存按钮可读可点。
- [ ] `pnpm test` 全绿(含既有)、`pnpm build`(tsc)通过、`pnpm e2e` 双 project 通过。

## 11. 风险与回滚

- **风险(低)**:纯新增页 + 加法接线。精度:提交侧「原文本透传」保护现实取值,读侧大 long 有损为已记录限制(不谎称根治);enabled 缺省/播种陷阱由「从 detail 播种 + 恒显式传值 + 两条测试」守住;state 串场景由 destroyOnHidden + key 重挂守住;权限仅前端门控(隐藏),后端 recon.launch matcher 兜底。
- **回滚**:删两个新组件 + 路由/菜单两条加法即回现状;无 DB/迁移/feature flag。
