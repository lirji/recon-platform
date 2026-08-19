# FINAL_PLAN · B5 冲正审批页(全栈)

> 决策依据见同目录 `DECISION_RECORD.md`。用户已定:**B 富化后端 DTO + 需填审批意见 + 工作流关闭态整页错误提示**。
> 本计划覆盖后端(recon-batch + recon-core + recon-workflow-flowable + Flyway V6)与前端(recon-console)。
> 日期:2026-08-19。

## 1. Goals / Non-goals

**Goals**
- 新增 `/reversal-approvals` 页:列出待审批冲正任务,**直显金额/币种/状态**(富化 DTO),支持**通过/驳回并强制填写审批意见**。
- 富化后端 `GET /recon/reversal-approvals` 返回:controller 层按 reversalId join `reversal_suggestion` 补金额/币种/状态/groupKey/runId。
- `decide` 全链加 `note`(审批意见),落库 `reversal_suggestion.decision_note`(Flyway V6 通用 ADD COLUMN)。
- 「提交审批」入口:`DiscrepancyDetailDrawer` 冲正建议行加按钮(`can('recon.dispose')`),调 `POST /submit`。
- 工作流关闭(默认)时:审批页整页 `ErrorState`「审批工作流未启用」(按 `code==='illegal_transition'` 特判)。
- 权限:读列表 `recon.read`(路由级已覆盖);通过/驳回/提交 `recon.dispose`(页内 `can` 门控)。
- 移动端沿用既有 `md?Table:卡片` 双轨;单测 + e2e(desktop+mobile)冒烟。

**Non-goals**
- 不做 B3 执行(`reversal-executions`)相关 UI——审批止于 CONFIRMED/DISCARDED。
- 不新增「按 run/status 列 SUGGESTED 建议」接口(决策 C 范畴,留后续)。
- 不改 operator 身份派生为 JWT(A1 加固,见风险节,本次仅标注)。
- 不加 submit 后端防重复/状态守卫(前端做基本防抖,硬约束留后续)。
- 不引入新状态库/新 UI 依赖/新视觉 token。

## 2. 视觉方向与设计参考(沿用既有,依据充分)

**默认沿用仓库既有设计系统**,零新 token:
- 主题 `src/theme/theme.ts` + `src/theme/colors.ts`(主色 `#315EFB`、成功 `#16A36A`、警告 `#D97706`、危险 `#D92D20`、圆角 8/12)。
- 业务 CSS 类 `src/styles/global.css`:`.page-header/.filter-card/.data-card/.mobile-data-card/.mono/.table-link`。
- 骨架页参考 `DiscrepanciesPage.tsx`(列表+移动端+三态最全);动作 Modal+Form 参考 `DiscrepancyDetailDrawer.tsx:196-216`。
- **冲正状态色映射**(新增 `ReversalStatusTag`,风格对齐 `dispositionColors`):
  `SUGGESTED→warning` · `CONFIRMED→processing` · `DISCARDED→default` · `EXECUTED→success` · `EXECUTION_FAILED→error`(**枚举含全 5 态,别漏 EXECUTION_FAILED**;审批页正常只出现 SUGGESTED/CONFIRMED/DISCARDED,但 Tag 组件覆盖全枚举以复用)。

## 3. 路由与页面流

```
侧边栏「冲正审批」→ /reversal-approvals
  RequireAuth(recon.read)  ← 路由级(router.tsx 现有受保护区兜底)
  ReversalApprovalsPage
    ├─ useQuery(['reversal-approvals']) → listReversalApprovals()
    ├─ isError:
    │    code==='illegal_transition' → 整页 ErrorState「审批工作流未启用」(无重试按钮 or 重试)
    │    其他 → 通用 ErrorState(onRetry=refetch)
    ├─ 空数据 → EmptyState「暂无待审批任务」
    └─ 数据 → md?Table:卡片; 每行/卡片 [通过][驳回](can('recon.dispose') 才渲染)
         点击 → Modal+Form(审批意见必填) → decideReversalApproval(taskId, approved, operator, note)
             onSuccess → message.success + invalidate(['reversal-approvals'], ['discrepancies'], ['dashboard'])
             onError 400 bad_request(task 已被处理) → message.warning「该审批任务已被处理」+ refetch
             onError 其他 → message.error(errorMessage)

提交审批(独立入口):
  DiscrepanciesPage → 差异详情抽屉 → 冲正建议 Collapse 每行 [提交审批](can('recon.dispose'))
     → submitReversalApproval(reversalEntry.id)
        onSuccess → message.success「已提交审批」+ invalidate(['discrepancy-detail', id])
        onError 409 illegal_transition → message.error「审批工作流未启用」
```

## 4. 组件树(复用现有 vs 新建)

| 组件 | 复用/新建 | 说明 |
|---|---|---|
| `PageHeader` | 复用 | 页头 |
| `AsyncState`(PageSkeleton/ErrorState/EmptyState) | 复用 | 三态 |
| `formatMinor/formatDateTime/errorMessage` | 复用 | 格式化 |
| `ReversalStatusTag` | **新建**(加进 `StatusTag.tsx`) | 5 态色映射 |
| `ReversalApprovalsPage` | **新建** | 列表页 + 通过/驳回 Modal |
| `ApprovalDecisionModal`(可内联页内) | **新建**(内联即可,不必单独文件) | Modal+Form 意见必填 + approved 语义 |
| 差异抽屉「提交审批」按钮 | **改** `DiscrepancyDetailDrawer.tsx` | 冲正建议行加操作列/按钮 |

## 5. 状态与边界(逐项)

| 场景 | 表现 |
|---|---|
| 加载中 | Table `loading` / `PageSkeleton` |
| 列表为空 | `EmptyState`「暂无待审批任务」(非 filtered) |
| 工作流未启用(409 illegal_transition) | 整页 `ErrorState`「审批工作流未启用,请联系管理员开启 recon.workflow.flowable.enabled」 |
| 其他接口错误 | 通用 `ErrorState` + 重试 |
| 无 recon.dispose 的 viewer | 能看列表;通过/驳回/提交按钮**不渲染** |
| 审批意见为空 | Form `rules` 必填校验,阻断提交(不发请求) |
| 重复审批(400 bad_request) | `message.warning`「该审批任务已被处理」+ 刷新列表 |
| find 不到 reversal(富化 join miss) | 金额/币种/状态显「—」;页面显式 `row.suggestedAmountMinor == null ? '—' : formatMinor(...)`(`formatMinor` **不**接受 null,不可依赖自动回退) |
| 金额精度 | 全程十进制字符串 + `BigInt`;后端 `suggested_amount_minor(long)` 序列化为 String |

## 6. API 契约

### 6.1 后端改动
**富化 `GET /recon/reversal-approvals`** — 返回 `List<PendingApprovalView>`:
```jsonc
{
  "taskId": "tk-1",
  "reversalId": "rev-abc",
  "createdAt": "2026-08-19T10:20:00Z",
  "suggestedAmountMinor": "1234",   // String(分),join reversal_suggestion;miss 时 null
  "currency": "USD",                 // miss 时 null
  "status": "SUGGESTED",             // miss 时 null
  "groupKey": "...",                 // miss 时 null
  "runId": "..."                     // miss 时 null
}
```
- 实现:`ReversalApprovalController.pending()` 对 `workflow.listPending()` 每条 `reversalId` 调 `reversalSuggestions.find(reversalId)` 富化;注入 `ReversalSuggestionRepository`。
- `suggestedAmountMinor` 序列化为 **String**(`Long.toString`),对齐 `ReversalEntry` 与前端 `formatMinor`。

**`decide` 加 note** — `POST /recon/reversal-approvals/{taskId}/decide?approved=&operator=&note=`:
- controller 加 `@RequestParam(required=false) String note`,透传 `workflow.decide(taskId, approved, operator, note)`。

### 6.2 前端 API 层(`src/api/recon.ts` + `src/api/types.ts`)
```ts
// types.ts
export interface PendingApprovalView {
  taskId: string
  reversalId: string | null
  createdAt: string
  suggestedAmountMinor: string | null
  currency: string | null
  status: string | null
  groupKey: string | null
  runId: string | null
}
// recon.ts
listReversalApprovals(): Promise<PendingApprovalView[]>            // GET
submitReversalApproval(reversalId: string): Promise<string>       // POST /submit?reversalId  (body=null, params)
decideReversalApproval(taskId, approved: boolean, operator?: string, note?: string): Promise<void>
                                                                   // POST /{taskId}/decide  (body=null, params)
```

## 7. 响应式与移动端适配

| 断点 | 策略 |
|---|---|
| `screens.md`(≥768)以上 | `<Table scroll={{x: 900}} pagination={false}>`,列:冲正建议ID(mono)/金额/币种/状态Tag/创建时间/操作 |
| `md` 以下 | `.mobile-data-card` 卡片堆叠:标题=金额+币种,副行=状态Tag+时间+reversalId(mono 截断),卡片内 [通过][驳回] 按钮(`min-height:44px`) |
| Modal(审批意见) | 沿用 AntD Modal,小屏自适应;Form `layout="vertical"` |
| 菜单 | 沿用 `AppLayout` 窄屏抽屉菜单(navigation 数组加项即可) |

**移动端验收**:Pixel5 project 下能经「打开菜单」进入审批页、列表渲染为卡片、通过按钮可点触发 Modal。

## 8. 文件级改动清单

**后端**
1. `recon-batch/.../db/migration/V6__reversal_decision_note.sql`(**新**):`ALTER TABLE reversal_suggestion ADD COLUMN decision_note VARCHAR(512) NULL;`(通用,三方言。核验:现最大版本 V5,V6 空闲;放 db/migration 三方言均执行一次)。
2. `recon-core/.../port/out/ReversalSuggestionRepository.java`(**改**):**不改旧签名,用 3 参 default 重载委托到 4 参抽象方法**(评审 B1:避免打断既有调用方):
   ```java
   default int updateStatus(String id, ReversalStatus status, String operator) {
       return updateStatus(id, status, operator, null);   // B3 执行等旧调用方零改动
   }
   int updateStatus(String id, ReversalStatus status, String operator, String note);
   ```
3. `recon-batch/.../persistence/JdbcReversalSuggestionStore.java`(**改**):实现 4 参 `updateStatus`,SQL 加 `decision_note = ?`(null 时写 null 覆盖为 null—B3 执行经 default 传 null 不清空?见下注)。`find` **不必**读 decision_note(审批页不回读 note)。
   > 注:B3 执行走 3 参 default → note=null。若直写 `SET decision_note=?` 会把已存审批意见清成 null。**修正**:4 参实现里 `note==null` 时 SQL 不含 decision_note 列(或 `decision_note = COALESCE(?, decision_note)`),保证 B3 执行不抹掉 B5 审批意见。
4. `recon-workflow-flowable/.../ReversalDecisionSink.java`(**改**):`onDecision` 加 `String note`(`@FunctionalInterface` 保持)。
5. `recon-workflow-flowable/.../ReversalStatusListener.java`(**改**):`execution.getVariable("note")` 读入,传入 sink。
6. `recon-workflow-flowable/.../ReversalApprovalWorkflow.java`(**改**):`decide(taskId, approved, operator, note)`;`taskService.complete` 变量加 `note`,**note 做 null 合并**(评审 M1:`Map.of` 不接受 null,现 operator 已 `==null?"":`,note 同样处理,否则漏传 note→NPE→500)。
7. `recon-batch/.../config/WorkflowConfig.java`(**改**):sink lambda 加 note → `updateStatus(reversalId, status, operator, note)`。
8. `recon-batch/.../web/ReversalApprovalController.java`(**改**):注入 `ReversalSuggestionRepository`;`pending()` 富化为 `PendingApprovalView`;`decide` 加 `@RequestParam(required=false) String note`。
9. `recon-batch/.../web/PendingApprovalView.java`(**新**,record):富化 DTO,金额 String。
10. `recon-batch/.../service/ReversalExecutionService.java`(**不改**,评审确认):`:51/:55` 两处 3 参 `updateStatus` 经 default 重载走 note=null,零改动;由 step 3 的 COALESCE 保证不抹审批意见。
11. `recon-handler/.../DiscrepancyHandlerChainTest.java`(**改**,评审 B1 补漏):`FakeReversals implements ReversalSuggestionRepository` 必须新增 4 参 `@Override updateStatus`(default+abstract 组合下假实现缺 4 参仍编译失败)。
12. `recon-core/.../domain/model/ReversalSuggestion.java`(**不改**,YAGNI):审批页不回读 note,不加 `decisionNote` 字段。

**前端**
13. `recon-console/src/api/types.ts`(**改**):加 `PendingApprovalView`(金额/币种/状态/groupKey/runId 均 `string | null`)。
14. `recon-console/src/api/recon.ts`(**改**):加 3 个函数。
15. `recon-console/src/components/common/StatusTag.tsx`(**改**):加 `reversalLabels/reversalColors` + `ReversalStatusTag`。
16. `recon-console/src/pages/ReversalApprovalsPage.tsx`(**新**):列表 + 通过/驳回 Modal。**金额列显式 null 兜底**(评审 m1:`formatMinor` 不接受 null,不会自动回退):`row.suggestedAmountMinor == null ? '—' : formatMinor(row.suggestedAmountMinor, row.currency ?? undefined)`。
17. `recon-console/src/router.tsx`(**改**):lazy import + route `{ path: 'reversal-approvals' }`。
18. `recon-console/src/components/layout/AppLayout.tsx`(**改**):navigation 加 `{ key:'/reversal-approvals', label:'冲正审批', icon:<SolutionOutlined/> }`(评审 m2:`/runs` 已用 `AuditOutlined`,改用未占用图标)。
19. `recon-console/src/components/discrepancies/DiscrepancyDetailDrawer.tsx`(**改**):冲正建议 Collapse 加「提交审批」按钮 + submit mutation(`can('recon.dispose')` 门控)。

**测试**
20. `recon-console/src/pages/ReversalApprovalsPage.test.tsx`(**新**):加载/空/409关闭态/通过驳回 mutation/权限门控/意见必填(注意 matchMedia.matches=false → 断言卡片 DOM)。
21. `recon-console/e2e/console.smoke.spec.ts`(**改**):`mockApi` 加 3 路由(list/submit/decide,插在 404 兜底前),加一条审批冒烟(desktop+mobile)。
22. `recon-workflow-flowable/.../ReversalApprovalWorkflowTest.java`(**改**):decide note 透传断言(捕获 sink 收到 note;并加 note=null 不 NPE 用例)。
23. `recon-batch/.../ReversalApprovalWorkflowIT.java`(**改**):approve/reject 后查 DB 断言 `decision_note` 落库;并断言随后 B3 执行(3 参 default)不清空 decision_note。
24. `recon-batch/.../web/*`(**新/改**):controller 富化 `PendingApprovalView`(金额 String、miss→null)+ decide note 切片测试。

## 9. 实施步骤(按依赖排序)

1. **DB**:写 V6 迁移(ADD COLUMN)。跑 `./mvnw -q -pl recon-batch test` 确认 Flyway 迁移不破坏既有 IT。
2. **core 端口**:`ReversalSuggestionRepository.updateStatus` 加 note;`JdbcReversalSuggestionStore` 实现 SQL。
3. **workflow 链**:`ReversalDecisionSink`→`ReversalStatusListener`→`ReversalApprovalWorkflow.decide` 加 note;`WorkflowConfig` sink 接线。跑 recon-workflow-flowable 模块测试。
4. **controller**:注入 repo,`pending()` 富化 `PendingApprovalView`,`decide` 加 note。跑 recon-batch 测试(含 IT approve/reject + note 落库)。
5. **前端 api/types/StatusTag**:加类型、3 函数、ReversalStatusTag。
6. **前端页面**:`ReversalApprovalsPage`(列表+Modal+三态+关闭态+权限+响应式)。
7. **前端接线**:router + AppLayout 菜单。
8. **submit 入口**:`DiscrepancyDetailDrawer` 加「提交审批」按钮 + mutation。
9. **前端测试**:page 单测 + e2e mock/冒烟。
10. **全量回归**:`./mvnw -q test`(9 模块绿 + ArchUnit)；`cd recon-console && pnpm test && pnpm build && pnpm e2e`。

## 10. 测试策略

**后端**
- `ReversalApprovalWorkflowTest`:decide 传 note → sink 收到 note(捕获实现断言)。
- `ReversalApprovalWorkflowIT`(门控开):approve→CONFIRMED + decision_note 落库;reject→DISCARDED + note 落库(查 DB 或 find)。
- controller 富化:mock workflow.listPending + repo.find,断言 `PendingApprovalView` 金额为 String、miss 时 null。

**前端单测**(`renderApp` + `vi.mock('../api/recon')`,注意 `matchMedia.matches=false` → 走移动端卡片分支):
- (a) 加载成功渲染金额/状态Tag/时间;(b) 409 illegal_transition → 整页错误文案「审批工作流未启用」(非空态、非「刷新」文案);(c) 空列表 → EmptyState;(d) recon.dispose 用户点通过 → Modal → 填意见 → decideReversalApproval 被调 + toast + invalidate;(e) `mockAuth({permissions:['recon.read']})` → 通过/驳回按钮不在文档;(f) 意见留空 → 校验阻断、`decideReversalApproval` 未被调;(g) 400 bad_request → warning「已被处理」+ refetch。

**e2e**(desktop-chromium + mobile-chromium 双 project,`mockApi` 加 3 路由于 404 兜底前):进入审批页 → 列表渲染 → 点通过 → 填意见 → 断言 decide POST 发出 + 成功 toast;移动端经「打开菜单」导航。

## 11. 验收标准

- [ ] `./mvnw -q test` 9 模块全绿,各模块 ArchUnit 门禁过(recon-core 无框架泄漏、workflow 的 org.flowable 不外泄)。
- [ ] `pnpm test`(含新 page 单测)全绿;`pnpm build` 通过;`pnpm e2e` desktop+mobile 全绿。
- [ ] 审批页直显金额/币种/状态(富化 DTO 生效);通过/驳回强制填意见并落库 `decision_note`。
- [ ] 工作流关闭时审批页显「审批工作流未启用」整页错误(非空态、非版本冲突文案)。
- [ ] 无 recon.dispose 用户看得到列表、看不到动作按钮。
- [ ] **移动端(Pixel5)**:能进审批页、列表为卡片、通过按钮可点触发 Modal(至少一条移动端 e2e 断言)。
- [ ] 金额全程十进制字符串/BigInt,无 number 精度损失。

## 12. 风险与回滚

| 风险 | 缓解 |
|---|---|
| **409 双语义混淆**(工作流未启用 vs 版本冲突) | 严格按 `error.code==='illegal_transition'` 分支,单测 (b) 专门锁定 |
| **[评审 B1] `updateStatus` 改签名打断既有调用方** | **不改旧签名**——3 参 default 委托到 4 参抽象(`ReversalExecutionService` 两处零改动);`DiscrepancyHandlerChainTest.FakeReversals` 补 4 参 `@Override`(已入清单 item 11) |
| **[评审 M1] note=null → `Map.of` NPE→500** | `decide` 里 note 做 `==null?"":` 合并(item 6);单测覆盖 note=null 不 NPE(item 22) |
| **[评审 step3] B3 执行(note=null)抹掉审批意见** | 4 参实现用 `decision_note = COALESCE(?, decision_note)` 或 note==null 时不含该列;IT 断言执行后 note 不丢(item 23) |
| Flyway V6 ADD COLUMN 方言差异 | 用最通用 `ADD COLUMN ... VARCHAR(512) NULL`;`RealDbEndToEndIT`/H2 IT 覆盖(评审确认 H2/MySQL8/PG 语法均合法) |
| **operator 可信性**(secure 下未从 JWT 派生) | 本次沿用现状(前端传 user.name);标注建议后续对齐 B2/A1,**不在本次改** |
| submit 无防重复 | 前端提交后禁用/刷新;后端硬约束留后续 |
| 富化 join N 次 find(待办多时) | 待办量有界(人工审批队列);如需可后续加 batch find,不在本次 |
| **回滚** | 后端:删 V6(未上生产前)+ 还原 4 参重载(旧 3 参签名未动,回滚更省)+ sink/listener/controller;前端:纯新增页 + 3 处注册行 + 抽屉一按钮,删除即回滚。不改既有算法/守恒/分类逻辑,回滚面清晰 |
