import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Empty, Grid, Pagination, Space, Table, Typography } from 'antd'
import { listRunRejects } from '../../api/recon'
import type { RejectEntry } from '../../api/types'
import { errorMessage, formatDateTime } from '../../utils/format'
import { ErrorState, PageSkeleton } from '../common/AsyncState'

interface Props {
  runId: string
  enabled: boolean
}

export function RejectsPanel({ runId, enabled }: Props) {
  const screens = Grid.useBreakpoint()
  const [page, setPage] = useState({ page: 0, size: 20 })
  const rejects = useQuery({
    queryKey: ['run-rejects', runId, page],
    queryFn: () => listRunRejects(runId, page),
    enabled: enabled && Boolean(runId),
  })

  if (rejects.isPending) return <PageSkeleton />
  if (rejects.isError) return <ErrorState message={errorMessage(rejects.error)} onRetry={() => void rejects.refetch()} />

  const rows = rejects.data?.content || []
  if (rows.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该 Run 没有载入期拒绝行" />
  }

  const columns = [
    { title: '段', dataIndex: 'segmentId', width: 140, render: (value: string | null) => value || '—' },
    { title: '来源角色', dataIndex: 'sourceRole', width: 110, render: (value: string | null) => value || '—' },
    {
      title: '血缘',
      dataIndex: 'rawRef',
      width: 220,
      render: (value: string | null) => <span className="mono">{value || '—'}</span>,
    },
    { title: '原因', dataIndex: 'reason', width: 200, render: (value: string | null) => value || '—' },
    {
      title: '原始行',
      dataIndex: 'rawPayload',
      ellipsis: true,
      render: (value: string | null) => <Typography.Text className="mono" ellipsis={{ tooltip: value }}>{value || '—'}</Typography.Text>,
    },
    { title: '时间', dataIndex: 'createdAt', width: 180, render: formatDateTime },
  ]

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Typography.Text type="secondary">
        标准化失败的源行会记入拒绝表后继续整流。重跑会清掉这些机器产物。
      </Typography.Text>
      {screens.md ? (
        <Table<RejectEntry>
          rowKey="id"
          size="small"
          pagination={false}
          columns={columns}
          dataSource={rows}
          scroll={{ x: 980 }}
        />
      ) : (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {rows.map((row) => (
            <div className="mobile-data-card" key={row.id} style={{ cursor: 'default' }}>
              <span className="mobile-card-heading">
                <strong>{row.sourceRole || '—'} · {row.segmentId || '—'}</strong>
                <span>{formatDateTime(row.createdAt)}</span>
              </span>
              <span className="mono mobile-card-id">{row.rawRef || row.id}</span>
              <span className="mobile-card-stats">
                <span>{row.reason || '—'}</span>
              </span>
              {row.rawPayload && <Typography.Paragraph type="secondary" ellipsis={{ rows: 2, expandable: true }}>{row.rawPayload}</Typography.Paragraph>}
            </div>
          ))}
        </Space>
      )}
      {rejects.data && rejects.data.totalElements > 0 && (
        <Pagination
          current={rejects.data.page + 1}
          pageSize={rejects.data.size}
          total={rejects.data.totalElements}
          showSizeChanger
          pageSizeOptions={[20, 50, 100]}
          onChange={(next, size) => setPage({ page: next - 1, size })}
        />
      )}
    </Space>
  )
}
