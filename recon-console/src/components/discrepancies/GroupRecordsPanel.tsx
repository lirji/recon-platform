import { useQuery } from '@tanstack/react-query'
import { Alert, Empty, Grid, Space, Table, Typography } from 'antd'
import { getGroupRecords } from '../../api/recon'
import type { GroupRecordDetail } from '../../api/types'
import { errorMessage, formatMinor } from '../../utils/format'
import { ErrorState, PageSkeleton } from '../common/AsyncState'

interface Props {
  runId: string
  segmentId: string
  groupKey: string | null
}

export function GroupRecordsPanel({ runId, segmentId, groupKey }: Props) {
  const screens = Grid.useBreakpoint()
  const report = useQuery({
    queryKey: ['group-records', runId, segmentId, groupKey],
    queryFn: () => getGroupRecords(runId, segmentId, groupKey!),
    enabled: Boolean(runId && segmentId && groupKey),
  })

  if (!groupKey) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无分组键，无法下钻组内明细" />
  }
  if (report.isPending) return <PageSkeleton />
  if (report.isError) return <ErrorState message={errorMessage(report.error)} onRetry={() => void report.refetch()} />

  const rows = report.data?.records || []
  if (rows.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该组没有 staged 记录" />
  }

  const columns = [
    { title: '侧', dataIndex: 'side', width: 80 },
    { title: '来源角色', dataIndex: 'sourceRole', width: 120 },
    {
      title: '匹配键',
      dataIndex: 'matchKey',
      width: 160,
      render: (value: string | null) => <span className="mono">{value || '—'}</span>,
    },
    {
      title: '金额',
      dataIndex: 'signedAmountMinor',
      width: 150,
      render: (value: string, row: GroupRecordDetail) => formatMinor(value, row.currency),
    },
    { title: '业务状态', dataIndex: 'bizStatus', width: 110, render: (value: string | null) => value || '—' },
    {
      title: '血缘',
      dataIndex: 'rawRef',
      render: (value: string | null) => <span className="mono">{value || '—'}</span>,
    },
  ]

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {report.data?.truncated && (
        <Alert type="warning" showIcon message="明细已截断，仅展示前 500 条 staged 记录。" />
      )}
      {screens.md ? (
        <Table<GroupRecordDetail>
          rowKey="recordId"
          size="small"
          pagination={false}
          columns={columns}
          dataSource={rows}
          scroll={{ x: 860 }}
        />
      ) : (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {rows.map((row) => (
            <div className="mobile-data-card" key={row.recordId} style={{ cursor: 'default' }}>
              <span className="mobile-card-heading">
                <strong>{row.side} · {row.sourceRole}</strong>
                <span>{formatMinor(row.signedAmountMinor, row.currency)}</span>
              </span>
              <span className="mono mobile-card-id">{row.matchKey || row.recordId}</span>
              <span className="mobile-card-stats">
                <span>{row.bizStatus || '—'}</span>
                <span className="mono">{row.rawRef || '—'}</span>
              </span>
            </div>
          ))}
        </Space>
      )}
      <Typography.Text type="secondary">共 {report.data?.recordCount} 条</Typography.Text>
    </Space>
  )
}
