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
  operator: string
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
