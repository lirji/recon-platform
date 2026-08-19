import { Tag } from 'antd'

/** 场景启用状态标签(颜色 + 中文,颜色非唯一手段)。 */
export function ScenarioEnabledTag({ enabled }: { enabled: boolean }) {
  return <Tag color={enabled ? 'success' : 'default'}>{enabled ? '启用' : '停用'}</Tag>
}
