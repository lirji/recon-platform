import { useQuery } from '@tanstack/react-query'
import { Alert, Table, Typography } from 'antd'
import { Link } from 'react-router-dom'
import { getRefineViolations } from '../../api/recon'
import type { RefineViolation } from '../../api/types'
import { errorMessage } from '../../utils/format'
import { ErrorState } from '../common/AsyncState'

interface Props {
  runId: string
}

export function RefineViolationsAlert({ runId }: Props) {
  const report = useQuery({
    queryKey: ['refine-violations', runId],
    queryFn: () => getRefineViolations(runId),
    enabled: Boolean(runId),
  })

  if (report.isError) {
    return <ErrorState message={errorMessage(report.error)} onRetry={() => void report.refetch()} />
  }
  if (!report.data || report.data.violationCount === 0) return null

  const columns = [
    { title: '分段', dataIndex: 'segmentId', width: 180 },
    {
      title: '匹配键',
      dataIndex: 'matchKey',
      render: (value: string) => <span className="mono">{value}</span>,
    },
    { title: '冲突 group 数', dataIndex: 'distinctGroupCount', width: 140 },
    {
      title: '排查',
      width: 120,
      render: (_: unknown, row: RefineViolation) => (
        <Link
          to={`/discrepancies?runId=${encodeURIComponent(runId)}&q=${encodeURIComponent(row.matchKey)}`}
        >
          查看差异
        </Link>
      ),
    },
  ]

  return (
    <Alert
      type="error"
      showIcon
      message="发现函数性 refine 违规"
      description={
        <div>
          <Typography.Paragraph style={{ marginBottom: 12 }}>
            同一匹配键映射到多个分组键，两侧会落入不同桶而永远不相遇，可能产生假「桥接断裂 / 多出」且守恒仍通过。
            {report.data.truncated ? ' 列表已截断，仅显示前 100 条。' : ''}
          </Typography.Paragraph>
          <Table<RefineViolation>
            rowKey={(row) => `${row.segmentId}:${row.matchKey}`}
            size="small"
            pagination={false}
            columns={columns}
            dataSource={report.data.violations}
            scroll={{ x: 640 }}
          />
        </div>
      }
    />
  )
}
