export interface UserSession {
  authenticated: boolean
  sub: string
  name: string
  permissions: string[]
}

export interface PageResult<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface DashboardMetrics {
  totalRuns: number
  runningRuns: number
  completedRuns: number
  failedRuns: number
  imbalancedRuns: number
  openDiscrepancies: number
  resolvedDiscrepancies: number
  closedDiscrepancies: number
}

export interface KeyCount {
  key: string
  count: number
}

export interface RunSummary {
  runId: string
  scenarioCode: string
  accountingPeriod: string
  sequenceNo: number
  status: string
  bucketCount: number
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  discrepancyCount: number
  openDiscrepancyCount: number
  balanced: boolean | null
}

export interface DashboardView {
  metrics: DashboardMetrics
  discrepancyTypes: KeyCount[]
  recentRuns: RunSummary[]
}

export interface ReportEntry {
  segmentId: string
  currency: string
  expectedTotalMinor: string
  matchedAmountMinor: string
  amountMismatchMinor: string
  missingMinor: string
  duplicateMinor: string
  extraMinor: string
  timingMinor: string
  statusMismatchMinor: string
  currencyMismatchMinor: string
  groupSumMismatchMinor: string
  bridgeBrokenMinor: string
  rightSideTotalMinor: string
  leftResidualMinor: string
  rightResidualMinor: string
  balanced: boolean
}

export interface RunDetail {
  run: RunSummary
  reports: ReportEntry[]
}

// B1 三方合并只读 roll-up。由后端从一个 Run 的两段报表(SEG1 营销↔账务、SEG2 账务↔渠道)派生。
// 金额一律十进制字符串,禁转 number;缺段时 seg1/seg2 为 null(链路不完整),threeWayBalanced 无报表时为 null。
export interface CurrencyRollup {
  currency: string
  seg1: ReportEntry | null
  seg2: ReportEntry | null
  threeWayConsistent: boolean
  bridgeBrokenMinor: string
}

export interface ThreeWayReport {
  runId: string
  scenarioCode: string
  accountingPeriod: string
  status: string
  threeWayBalanced: boolean | null
  currencies: CurrencyRollup[]
}

export interface DiscrepancySummary {
  discrepancyId: string
  runId: string
  scenarioCode: string
  accountingPeriod: string
  segmentId: string
  type: string
  bridgeBreakStage: string | null
  fingerprint: string
  groupKey: string | null
  matchKey: string | null
  currency: string | null
  expectedAmountMinor: string
  actualAmountMinor: string
  deltaAmountMinor: string
  leftRawRef: string | null
  rightRawRef: string | null
  dispositionStatus: string
  operator: string | null
  note: string | null
  dispositionVersion: number | null
  createdAt: string
  updatedAt: string
}

export interface ActionEntry {
  id: string
  actionType: string
  payload: string | null
  operator: string
  createdAt: string
}

export interface ReversalEntry {
  id: string
  runId: string
  groupKey: string | null
  suggestedAmountMinor: string
  currency: string
  status: string
  operator: string | null
  createdAt: string
}

export interface AlertEntry {
  id: string
  runId: string
  status: string
  attempt: number
  createdAt: string
  sentAt: string | null
}

// B5 冲正审批待办(富化):后端 controller join reversal_suggestion 补金额/币种/状态/血缘。
// 金额一律十进制字符串(禁转 number);join miss 时业务字段为 null。
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

export interface DiscrepancyDetail {
  discrepancy: DiscrepancySummary
  actions: ActionEntry[]
  reversals: ReversalEntry[]
  alerts: AlertEntry[]
}

export interface RunFilters {
  scenarioCode?: string
  accountingPeriod?: string
  status?: string
  page?: number
  size?: number
}

export interface DiscrepancyFilters {
  runId?: string
  type?: string
  status?: string
  segmentId?: string
  currency?: string
  q?: string
  page?: number
  size?: number
}

export interface LaunchRunRequest {
  scenarioCode: string
  accountingPeriod: string
  bucketCount: number
}

export interface LaunchResult {
  runId: string
  sequenceNo: number
  status: string
  jobExecutionId: number
}

export interface ClearRequest {
  // secure profile 由后端从 JWT 取(前端不必传);dev 回退用会话身份名。
  operator?: string
  note?: string
  expectedVersion?: number
}

export interface DispositionResponse {
  fingerprint: string
  segmentId: string
  status: string
  operator: string
  note: string | null
  version: number
  lastSeenRunId: string
}

// B4 配置驱动场景 · 场景定义 DSL(与后端 recon-scenario/dsl/ScenarioDefinition 对齐)。
export type SourceRole = 'MARKETING' | 'ACCOUNTING' | 'CHANNEL'
export type EvaluatorType = 'EXACT' | 'TOLERANCE' | 'DROOLS'
export type DiscrepancyKind =
  | 'BRIDGE_BROKEN'
  | 'CURRENCY_MISMATCH'
  | 'DUPLICATE'
  | 'EXTRA'
  | 'GROUP_SUM_MISMATCH'
  | 'AMOUNT_MISMATCH'
  | 'STATUS_MISMATCH'
  | 'TIMING'
  | 'MISSING'
  | 'FX_RATE_DIFF'

export interface ScenarioSource {
  sourceType: string
  params: Record<string, string>
}

export interface ScenarioRule {
  evaluatorType: EvaluatorType
  // 后端 long(最小货币单位);现实容差远小于 2^53。读侧经 JSON.parse,>2^53 会舍入(已知限制)。
  absToleranceMinor: number
  ratioToleranceBps: number
  enabledTypes: DiscrepancyKind[] | null
}

export interface ScenarioSegment {
  id: string
  leftRole: SourceRole
  rightRole: SourceRole
  spineRole: SourceRole | null
  stageLabel: string
  matchKeyField: string
  groupKeyField: string
  left: ScenarioSource
  right: ScenarioSource
  rule: ScenarioRule
}

export interface ScenarioDefinition {
  code: string
  segments: ScenarioSegment[]
}

export interface ScenarioSummary {
  code: string
  version: number
  enabled: boolean
  segmentCount: number
}

export interface ScenarioView {
  code: string
  version: number
  enabled: boolean
  definition: ScenarioDefinition
}
