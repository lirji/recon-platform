import { api } from './client'
import type {
  ClearRequest,
  DashboardView,
  DiscrepancyDetail,
  DiscrepancyFilters,
  DiscrepancySummary,
  DispositionResponse,
  LaunchResult,
  LaunchRunRequest,
  PageResult,
  PendingApprovalView,
  RunDetail,
  RunFilters,
  RunSummary,
  ScenarioSummary,
  ScenarioView,
  ThreeWayReport,
  UserSession,
  RefineViolationReport,
  GroupRecordReport,
  ReversalExecutionResult,
  RejectEntry,
  RejectFilters,
  RemediationView,
  RemediationFilters,
  ProposeRemediationRequest,
  RemediationDecision,
} from './types'

export async function getMe(): Promise<UserSession> {
  return (await api.get<UserSession>('/recon/auth/me')).data
}

export async function getDashboard(): Promise<DashboardView> {
  return (await api.get<DashboardView>('/recon/dashboard')).data
}

export async function listRuns(filters: RunFilters): Promise<PageResult<RunSummary>> {
  return (await api.get<PageResult<RunSummary>>('/recon/runs', { params: filters })).data
}

export async function getRun(runId: string): Promise<RunDetail> {
  return (await api.get<RunDetail>(`/recon/runs/${encodeURIComponent(runId)}`)).data
}

export async function getThreeWayReport(runId: string): Promise<ThreeWayReport> {
  return (await api.get<ThreeWayReport>(`/recon/runs/${encodeURIComponent(runId)}/three-way`)).data
}

/** A5/KI-6:同一 match_key 落多个 group_key 的脏跨表诊断。 */
export async function getRefineViolations(runId: string): Promise<RefineViolationReport> {
  return (await api.get<RefineViolationReport>(`/recon/runs/${encodeURIComponent(runId)}/refine-violations`)).data
}

/** B7:组(段 + group_key)底层 staged 记录明细。 */
export async function getGroupRecords(
  runId: string,
  segmentId: string,
  groupKey: string,
): Promise<GroupRecordReport> {
  return (
    await api.get<GroupRecordReport>(`/recon/runs/${encodeURIComponent(runId)}/records`, {
      params: { segmentId, groupKey },
    })
  ).data
}

// B4 场景管理:list 返回裸数组(无分页)。
export async function listScenarios(): Promise<ScenarioSummary[]> {
  return (await api.get<ScenarioSummary[]>('/recon/scenarios')).data
}

export async function getScenario(code: string): Promise<ScenarioView> {
  return (await api.get<ScenarioView>(`/recon/scenarios/${encodeURIComponent(code)}`)).data
}

/**
 * upsert 场景定义。body 发用户输入的<b>原始 JSON 文本</b>:axios 对合法 JSON string body 原样透传(不 re-stringify),
 * 故 absToleranceMinor 等 long 在提交侧不被 number 化丢精度。enabled 走 query 参数(务必显式传当前值)。
 */
export async function saveScenario(
  code: string,
  definitionJson: string,
  enabled: boolean,
): Promise<ScenarioView> {
  return (
    await api.put<ScenarioView>(`/recon/scenarios/${encodeURIComponent(code)}`, definitionJson, {
      params: { enabled },
      headers: { 'Content-Type': 'application/json' },
    })
  ).data
}

export async function launchRun(request: LaunchRunRequest): Promise<LaunchResult> {
  return (await api.post<LaunchResult>('/recon/runs', request)).data
}

export async function rerunRun(runId: string): Promise<LaunchResult> {
  return (await api.post<LaunchResult>(`/recon/runs/${encodeURIComponent(runId)}/rerun`)).data
}

export async function listDiscrepancies(
  filters: DiscrepancyFilters,
): Promise<PageResult<DiscrepancySummary>> {
  return (await api.get<PageResult<DiscrepancySummary>>('/recon/discrepancies', { params: filters })).data
}

export async function getDiscrepancy(discrepancyId: string): Promise<DiscrepancyDetail> {
  return (await api.get<DiscrepancyDetail>(`/recon/discrepancies/${encodeURIComponent(discrepancyId)}`)).data
}

export async function resolveDiscrepancy(
  discrepancyId: string,
  request: ClearRequest,
): Promise<DispositionResponse> {
  return (
    await api.post<DispositionResponse>(
      `/recon/discrepancies/${encodeURIComponent(discrepancyId)}/resolve`,
      request,
    )
  ).data
}

export async function closeDiscrepancy(
  discrepancyId: string,
  request: ClearRequest,
): Promise<DispositionResponse> {
  return (
    await api.post<DispositionResponse>(
      `/recon/discrepancies/${encodeURIComponent(discrepancyId)}/close`,
      request,
    )
  ).data
}

// B5 冲正审批:待办列表(富化,裸数组)、提交审批、审批决定。
// 工作流未启用(recon.workflow.flowable.enabled=false)时后端返 409 illegal_transition,由调用方按 code 兜底。
export async function listReversalApprovals(): Promise<PendingApprovalView[]> {
  return (await api.get<PendingApprovalView[]>('/recon/reversal-approvals')).data
}

export async function submitReversalApproval(reversalId: string): Promise<string> {
  return (await api.post<string>('/recon/reversal-approvals/submit', null, { params: { reversalId } })).data
}

export async function decideReversalApproval(
  taskId: string,
  approved: boolean,
  operator?: string,
  note?: string,
): Promise<void> {
  await api.post<void>(`/recon/reversal-approvals/${encodeURIComponent(taskId)}/decide`, null, {
    params: { approved, operator, note },
  })
}

/** B3:对 CONFIRMED(或 EXECUTION_FAILED 重试)冲正执行资金动作。需 recon.launch。 */
export async function executeReversal(
  reversalId: string,
  operator?: string,
): Promise<ReversalExecutionResult> {
  return (
    await api.post<ReversalExecutionResult>(
      `/recon/reversal-executions/${encodeURIComponent(reversalId)}/execute`,
      null,
      { params: { operator } },
    )
  ).data
}

/** 载入期拒绝行分页。 */
export async function listRunRejects(runId: string, filters: RejectFilters = {}): Promise<PageResult<RejectEntry>> {
  return (
    await api.get<PageResult<RejectEntry>>(`/recon/runs/${encodeURIComponent(runId)}/rejects`, { params: filters })
  ).data
}

export async function listRemediations(filters: RemediationFilters): Promise<PageResult<RemediationView>> {
  return (await api.get<PageResult<RemediationView>>('/recon/benefit-remediations', { params: filters })).data
}

export async function proposeRemediation(request: ProposeRemediationRequest): Promise<RemediationView> {
  return (await api.post<RemediationView>('/recon/benefit-remediations', request)).data
}

export async function approveRemediation(
  suggestionId: string,
  decision: RemediationDecision,
): Promise<RemediationView> {
  return (
    await api.post<RemediationView>(
      `/recon/benefit-remediations/${encodeURIComponent(suggestionId)}/approve`,
      decision,
    )
  ).data
}

export async function rejectRemediation(
  suggestionId: string,
  decision: RemediationDecision,
): Promise<RemediationView> {
  return (
    await api.post<RemediationView>(
      `/recon/benefit-remediations/${encodeURIComponent(suggestionId)}/reject`,
      decision,
    )
  ).data
}
