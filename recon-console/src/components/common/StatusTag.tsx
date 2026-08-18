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

export function RunStatusTag({ status }: { status: string }) {
  return <Tag color={runColors[status] || 'default'}>{runLabels[status] || status}</Tag>
}

export function DispositionStatusTag({ status }: { status: string }) {
  return <Tag color={dispositionColors[status] || 'default'}>{dispositionLabels[status] || status}</Tag>
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

export function DiscrepancyTypeTag({ type }: { type: string }) {
  const severe = type === 'BRIDGE_BROKEN' || type === 'CURRENCY_MISMATCH'
  return <Tag color={severe ? 'error' : type === 'MISSING' ? 'warning' : 'blue'}>{discrepancyTypeLabels[type] || type}</Tag>
}
