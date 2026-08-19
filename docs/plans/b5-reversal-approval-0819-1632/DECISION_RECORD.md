# 决策记录 · B5 冲正审批页(全栈)

> 走 frontend-plan 工作流:5 路只读子代理(需求流 / UI·UX / 前端架构 / 移动端 / 测试·风险)勘察后综合。
> 用户已就三个根本分叉拍板(见「已定决策」)。本记录留档备选与理由,供 `/codex-review` 对照实际 diff。
> 日期:2026-08-19。

## 背景事实(经子代理 + 直接核准,带出处)

- 后端 B5 三接口已存在:`ReversalApprovalController`(`recon-batch/.../web/ReversalApprovalController.java`)
  - `GET /recon/reversal-approvals` → `List<PendingApproval>`(裸数组,无分页),权限 `recon.read`(兜底 matcher)。
  - `POST /recon/reversal-approvals/submit?reversalId=` → `String`(流程实例 id),权限 `recon.dispose`。
  - `POST /recon/reversal-approvals/{taskId}/decide?approved=&operator=` → `void`,权限 `recon.dispose`。
- 待办 DTO 仅三字段:`PendingApproval(taskId, reversalId, createdAt)`(`ReversalApprovalWorkflow.java:68`)——**无金额/币种/状态**。
- **后端无「按 reversalId 查冲正金额」的 GET**,`ReversalEntry`(金额/币种)只嵌在 `GET /recon/discrepancies/{id}` 返回里,且 `PendingApproval` 无 `discrepancyId` 可反查 → 前端仅凭 reversalId **无法**补齐金额。
- `reversal_suggestion` 表(`db/migration/V1__recon_schema.sql`):`id/fingerprint/run_id/group_key/suggested_amount_minor(BIGINT)/currency/status/idempotency_key/operator/created_at`,**无 note 列**。
- `ReversalSuggestionRepository.updateStatus(id,status,operator)` 现为 `UPDATE ... SET status=?, operator=? WHERE id=?`(`JdbcReversalSuggestionStore.java:44`)。
- 审批终局回写链:`ReversalStatusListener`(读 `reversalId/operator` 流程变量)→ `ReversalDecisionSink.onDecision(reversalId,status,operator)` → `WorkflowConfig` 实现调 `updateStatus`。
- 异常映射(`ReconApiExceptionHandler`):`IllegalStateException→409 illegal_transition`;`IllegalArgumentException→400 bad_request`;`NotFoundException→404`;`ConflictException→409 conflict`。
- **工作流默认关**(`recon.workflow.flowable.enabled=false`);关闭时三接口经 `ObjectProvider` 抛 `IllegalStateException`→**409 illegal_transition**(不是 500)。
- 重复审批同一 taskId:后者拿 **400 bad_request**(task 已 complete 消失)。
- Flyway locations:`db/migration`(通用)+ `db/schema/{vendor}`(方言)+ `db/batch/{vendor}`,全局按版本号排序;现最大 V5 → **下一个通用迁移 V6**。
- 前端约定:React Query 内联(query key 数组字面量)、axios `api`(Bearer 拦截 + `ApiError{status,code}`)、金额一律十进制字符串 + `BigInt`(`utils/format.ts:formatMinor`)、`App.useApp().message` toast、`AsyncState`(PageSkeleton/ErrorState/EmptyState)、`StatusTag`、`can('recon.dispose')` 门控、`Grid.useBreakpoint()` 的 `md?Table:卡片` 双轨响应式、Playwright desktop+mobile 双 project。

## 决策 1 · 审批页信息密度 / 后端边界 —— 【已定:B 富化后端 DTO】

| 备选 | 做法 | Trade-off |
|---|---|---|
| A 零后端·瘦审批页 | 只对接现成 3 接口;审批列表仅显 reversalId/taskId/时间 | 零后端改动、回滚最小;**审批人看不到金额,盲批、风控弱** |
| **B 富化后端 DTO(选定)** | controller 层对每条待办 `find(reversalId)` join `reversal_suggestion` 补金额/币种/状态,审批页直显金额 | 审批体验完整、可审计;需动后端(controller + 新 DTO),`recon-workflow-flowable` 不改,回滚面限于 recon-batch |
| C 全生命周期页 + 后端 list | 新增 `ReversalSuggestionRepository.list` + `GET /recon/reversal-suggestions`,前端做全态列表+筛选 | 最完整;改动最大(core 端口+JDBC+controller+安全 matcher),超出「审批页」范畴 |

**选 B 理由**:审批要动钱,审批人必须看到金额/币种/状态才能可审计地批;B 用组合根 controller join 现成 `find(id)`,`recon-workflow-flowable` 模块零改动、职责干净;不引入 core 端口新查询(那是 C 的范畴,留给后续)。

## 决策 2 · 审批是否填写意见 —— 【已定:需填审批意见】

| 备选 | Trade-off |
|---|---|
| 无备注·一键(Popconfirm) | 契合现有 decide(无 note),零后端;但驳回无留痕、审计弱 |
| **需填审批意见(选定)** | Modal+Form 强制填意见;需 decide 加 note 参数 + `reversal_suggestion` 加列落库(Flyway V6)+ sink 链传 note。审计完整 |

**选定后果**:后端 decide 全链(controller→workflow→listener→sink→updateStatus)加 note 参数;`reversal_suggestion` 加 `decision_note VARCHAR(512) NULL`(通用 `ADD COLUMN`);前端通过/驳回都用 Modal+Form,意见必填。

### note 落库方案裁决
- **方案 X(选定):`reversal_suggestion` 加 `decision_note` 列**。note 与被审批对象同表,查询/展示最直接;一次通用 `ALTER TABLE ADD COLUMN VARCHAR` 三方言(MySQL8/PG/H2)语法一致;不违反 ADR-7(只加列存审批留痕,不删不动金额身份)。
- 方案 Y(否决):note 记 `discrepancy_action` 审计表。需由 `runId+fingerprint` 派生 `discrepancy_id`、链路长、审计表无现成 note 字段,更绕。

## 决策 3 · 工作流关闭态 UX —— 【已定:整页错误提示】

后端关闭时返 **409 illegal_transition**。前端整页 `ErrorState` 显「审批工作流未启用」。
**关键**:不可照抄 `DiscrepancyDetailDrawer` 的「409=版本冲突→刷新」特判(语义错乱);须按 `error.code === 'illegal_transition'` 显专用文案;其余 409 才按并发冲突处理。

## 决策 4 · submit(提交审批)入口位置 —— 【推导定:差异详情抽屉】

选 B 无「list SUGGESTED 建议」接口,审批页无法自列「可提交的建议」。唯一能拿到 `reversalId`(=`ReversalEntry.id`)的现有 UI 是 `DiscrepancyDetailDrawer` 的「冲正建议」Collapse。故 submit 入口挂在该处每行(`can('recon.dispose')` 门控),调 `submitReversalApproval(row.id)`。审批页只承载「列待办 + decide」。此为选 B 的自然结论,非新范围。

## 移动端 —— 不列 non-goal

仓库移动端是一等公民(4 页 + 3 抽屉 + 布局全适配,e2e 强制 Pixel5 project)。新页沿用 `md?Table(scroll x):卡片堆叠` + Drawer/Modal 小屏 `100%`。

## 诚实边界 / 已知风险(留给后续,不在本次强行扩)

- **operator 可信性**:decide/submit 的 operator 目前是裸参数(secure 下**未**像 B2 `DiscrepancyController` 那样从 JWT claim 派生),审批人身份可被请求方伪造。本次保持与 B3 execute 一致(前端传 `auth.user?.name`),**在 FINAL_PLAN 风险节标注建议后续对齐 A1**,不在本次强行改以免范围蔓延。
- **submit 无防重复/状态守卫**:同一 reversalId 可被多次 submit 产生多个并存待办;后端不校验「仅 SUGGESTED 可提交」。本次前端做基本防抖(提交后禁用/刷新),后端硬约束留后续。
