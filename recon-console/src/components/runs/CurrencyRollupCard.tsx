import { Alert, Card, Col, Descriptions, Row, Space, Tag, Typography } from 'antd'
import { Link } from 'react-router-dom'
import type { CurrencyRollup, ReportEntry } from '../../api/types'
import { formatMinor, isNonZeroMinor } from '../../utils/format'

// 段码 → 业务名(MVP 唯一三方场景 MARKETING_3WAY 的两段)。
const segmentLabels: Record<string, string> = {
  SEG1_MKT_ACCT: 'SEG1 营销 ↔ 账务',
  SEG2_ACCT_CHANNEL: 'SEG2 账务 ↔ 渠道',
}

function segmentLabel(segmentId: string): string {
  return segmentLabels[segmentId] || segmentId
}

interface SegmentPanelProps {
  label: string
  fallbackSegmentId: string
  seg: ReportEntry | null
  currency: string
  runId: string
}

function SegmentPanel({ label, fallbackSegmentId, seg, currency, runId }: SegmentPanelProps) {
  if (!seg) {
    return (
      <div>
        <div className="section-title" style={{ fontSize: 14 }}>{label}</div>
        <Alert type="warning" showIcon message={`链路不完整（缺 ${label}）`} />
      </div>
    )
  }
  const bridgeBroken = isNonZeroMinor(seg.bridgeBrokenMinor)
  const drillTo = `/discrepancies?runId=${encodeURIComponent(runId)}&segmentId=${encodeURIComponent(
    seg.segmentId || fallbackSegmentId,
  )}&type=BRIDGE_BROKEN`
  return (
    <div>
      <div className="section-title" style={{ fontSize: 14 }}>{segmentLabel(seg.segmentId) || label}</div>
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="应对金额">{formatMinor(seg.expectedTotalMinor, currency)}</Descriptions.Item>
        <Descriptions.Item label="已匹配">{formatMinor(seg.matchedAmountMinor, currency)}</Descriptions.Item>
        <Descriptions.Item label="缺失">{formatMinor(seg.missingMinor, currency)}</Descriptions.Item>
        <Descriptions.Item label="金额差">{formatMinor(seg.amountMismatchMinor, currency)}</Descriptions.Item>
        <Descriptions.Item label="桥断额">
          <Space size={8} wrap>
            <Typography.Text type={bridgeBroken ? 'danger' : undefined}>
              {formatMinor(seg.bridgeBrokenMinor, currency)}
            </Typography.Text>
            {bridgeBroken && <Link to={drillTo}>查看桥断差异</Link>}
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="守恒">
          <Typography.Text type={seg.balanced ? 'success' : 'danger'}>{seg.balanced ? '通过' : '异常'}</Typography.Text>
        </Descriptions.Item>
      </Descriptions>
    </div>
  )
}

export function CurrencyRollupCard({ rollup, runId }: { rollup: CurrencyRollup; runId: string }) {
  const currencyBridgeBroken = isNonZeroMinor(rollup.bridgeBrokenMinor)
  return (
    <Card
      size="small"
      title={
        <Space size={8} wrap>
          <span>{rollup.currency}</span>
          <Tag color={rollup.threeWayConsistent ? 'success' : 'error'}>
            {rollup.threeWayConsistent ? '一致' : '不一致'}
          </Tag>
        </Space>
      }
      extra={
        currencyBridgeBroken && (
          <Typography.Text type="danger">桥接断裂 {formatMinor(rollup.bridgeBrokenMinor, rollup.currency)}</Typography.Text>
        )
      }
    >
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <SegmentPanel label="SEG1 营销 ↔ 账务" fallbackSegmentId="SEG1_MKT_ACCT" seg={rollup.seg1} currency={rollup.currency} runId={runId} />
        </Col>
        <Col xs={24} md={12}>
          <SegmentPanel label="SEG2 账务 ↔ 渠道" fallbackSegmentId="SEG2_ACCT_CHANNEL" seg={rollup.seg2} currency={rollup.currency} runId={runId} />
        </Col>
      </Row>
    </Card>
  )
}
