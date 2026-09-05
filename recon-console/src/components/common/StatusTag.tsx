import { Tag } from 'antd'

const runLabels: Record<string, string> = {
  CREATED: '已创建',
  LOADING: '装载中',
  MATCHING: '匹配中',
  COMPLETED: '已完成',
  REPORT_IMBALANCE: '守恒异常',
  FAILED: '执行失败',
}

const dispositionLabels: Record<string, string> = {
  OPEN: '待处理',
  RESOLVED: '已核销',
  CLOSED: '已关闭',
  SUPPRESSED: '已抑制',
  REOPENED: '已重开',
  STALE: '已失效',
}

const runColors: Record<string, string> = {
  CREATED: 'default',
  LOADING: 'processing',
  MATCHING: 'processing',
  COMPLETED: 'success',
  REPORT_IMBALANCE: 'error',
  FAILED: 'error',
}

const dispositionColors: Record<string, string> = {
  OPEN: 'warning',
  RESOLVED: 'processing',
  CLOSED: 'success',
  SUPPRESSED: 'purple',
  REOPENED: 'warning',
  STALE: 'default',
}

// B5 冲正建议/审批状态(后端权威枚举含全 5 态,勿漏 EXECUTION_FAILED)。审批页正常出现 SUGGESTED/CONFIRMED/DISCARDED。
const reversalLabels: Record<string, string> = {
  SUGGESTED: '待审批',
  CONFIRMED: '已通过',
  DISCARDED: '已驳回',
  EXECUTED: '已执行',
  EXECUTION_FAILED: '执行失败',
}

const reversalColors: Record<string, string> = {
  SUGGESTED: 'warning',
  CONFIRMED: 'processing',
  DISCARDED: 'default',
  EXECUTED: 'success',
  EXECUTION_FAILED: 'error',
}

export function RunStatusTag({ status }: { status: string }) {
  return <Tag color={runColors[status] || 'default'}>{runLabels[status] || status}</Tag>
}

export function DispositionStatusTag({ status }: { status: string }) {
  return <Tag color={dispositionColors[status] || 'default'}>{dispositionLabels[status] || status}</Tag>
}

export function ReversalStatusTag({ status }: { status: string }) {
  return <Tag color={reversalColors[status] || 'default'}>{reversalLabels[status] || status}</Tag>
}

const alertLabels: Record<string, string> = {
  PENDING: '待投递',
  SENT: '已发送',
  FAILED: '投递失败',
}

const alertColors: Record<string, string> = {
  PENDING: 'warning',
  SENT: 'success',
  FAILED: 'error',
}

export function AlertStatusTag({ status }: { status: string }) {
  return <Tag color={alertColors[status] || 'default'}>{alertLabels[status] || status}</Tag>
}

export const discrepancyTypeLabels: Record<string, string> = {
  BRIDGE_BROKEN: '桥接断裂',
  CURRENCY_MISMATCH: '币种不符',
  DUPLICATE: '重复记录',
  EXTRA: '多出记录',
  GROUP_SUM_MISMATCH: '组总额不符',
  AMOUNT_MISMATCH: '金额不符',
  STATUS_MISMATCH: '状态不符',
  TIMING: '时点差异',
  MISSING: '记录缺失',
  FX_RATE_DIFF: '汇率差异',
}

const remediationLabels: Record<string, string> = {
  PROPOSED: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  DISPATCHING: '派发中',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
  UNKNOWN: '未知',
}

const remediationColors: Record<string, string> = {
  PROPOSED: 'warning',
  APPROVED: 'processing',
  REJECTED: 'default',
  DISPATCHING: 'processing',
  SUCCEEDED: 'success',
  FAILED: 'error',
  UNKNOWN: 'default',
}

export function RemediationStatusTag({ status }: { status: string }) {
  return <Tag color={remediationColors[status] || 'default'}>{remediationLabels[status] || status}</Tag>
}

const remediationActionLabels: Record<string, string> = {
  REISSUE: '补发',
  REVERSE: '冲正',
  MANUAL_REVIEW: '人工复核',
}

export function RemediationActionTag({ action }: { action: string }) {
  return <Tag>{remediationActionLabels[action] || action}</Tag>
}

export function DiscrepancyTypeTag({ type }: { type: string }) {
  const severe = type === 'BRIDGE_BROKEN' || type === 'CURRENCY_MISMATCH'
  return <Tag color={severe ? 'error' : type === 'MISSING' ? 'warning' : 'blue'}>{discrepancyTypeLabels[type] || type}</Tag>
}
