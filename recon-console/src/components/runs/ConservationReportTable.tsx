import { Table, Typography } from 'antd'
import type { ReportEntry } from '../../api/types'
import { colors } from '../../theme/colors'
import { formatMinor, isNonZeroMinor } from '../../utils/format'

function minorCell(value: string, currency: string, emphasizeNonZero = false) {
  const danger = emphasizeNonZero && isNonZeroMinor(value)
  return (
    <Typography.Text type={danger ? 'danger' : undefined} style={danger ? { color: colors.error } : undefined}>
      {formatMinor(value, currency)}
    </Typography.Text>
  )
}

const columns = [
  { title: '分段', dataIndex: 'segmentId', width: 180 },
  { title: '币种', dataIndex: 'currency', width: 80 },
  {
    title: '应对金额',
    dataIndex: 'expectedTotalMinor',
    width: 150,
    render: (value: string, row: ReportEntry) => formatMinor(value, row.currency),
  },
  {
    title: '已匹配',
    dataIndex: 'matchedAmountMinor',
    width: 150,
    render: (value: string, row: ReportEntry) => formatMinor(value, row.currency),
  },
  {
    title: '缺失',
    dataIndex: 'missingMinor',
    width: 130,
    render: (value: string, row: ReportEntry) => minorCell(value, row.currency, true),
  },
  {
    title: '多出',
    dataIndex: 'extraMinor',
    width: 130,
    render: (value: string, row: ReportEntry) => minorCell(value, row.currency, true),
  },
  {
    title: '重复',
    dataIndex: 'duplicateMinor',
    width: 130,
    render: (value: string, row: ReportEntry) => minorCell(value, row.currency, true),
  },
  {
    title: '金额差',
    dataIndex: 'amountMismatchMinor',
    width: 130,
    render: (value: string, row: ReportEntry) => minorCell(value, row.currency, true),
  },
  {
    title: '桥断额',
    dataIndex: 'bridgeBrokenMinor',
    width: 130,
    render: (value: string, row: ReportEntry) => minorCell(value, row.currency, true),
  },
  {
    title: '左残差',
    dataIndex: 'leftResidualMinor',
    width: 130,
    render: (value: string, row: ReportEntry) => minorCell(value, row.currency, true),
  },
  {
    title: '右残差',
    dataIndex: 'rightResidualMinor',
    width: 130,
    render: (value: string, row: ReportEntry) => minorCell(value, row.currency, true),
  },
  {
    title: '守恒',
    dataIndex: 'balanced',
    fixed: 'right' as const,
    width: 90,
    render: (balanced: boolean) => (
      <Typography.Text type={balanced ? 'success' : 'danger'}>{balanced ? '通过' : '异常'}</Typography.Text>
    ),
  },
]

export function ConservationReportTable({ reports }: { reports: ReportEntry[] }) {
  return (
    <Table
      rowKey={(row) => `${row.segmentId}:${row.currency}`}
      columns={columns}
      dataSource={reports}
      pagination={false}
      size="small"
      scroll={{ x: 1560 }}
    />
  )
}
