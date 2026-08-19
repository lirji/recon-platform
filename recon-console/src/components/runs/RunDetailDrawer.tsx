import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { App, Button, Descriptions, Drawer, Empty, Grid, Popconfirm, Space, Table, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { getRun, rerunRun } from '../../api/recon'
import type { ReportEntry } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { ErrorState, PageSkeleton } from '../common/AsyncState'
import { RunStatusTag } from '../common/StatusTag'
import { errorMessage, formatDateTime, formatMinor } from '../../utils/format'

interface Props {
  runId: string | null
  onClose: () => void
}

export function RunDetailDrawer({ runId, onClose }: Props) {
  const screens = Grid.useBreakpoint()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const canLaunch = useAuth().can('recon.launch')
  const detail = useQuery({
    queryKey: ['run-detail', runId],
    queryFn: () => getRun(runId!),
    enabled: Boolean(runId),
  })
  const rerun = useMutation({
    mutationFn: () => rerunRun(runId!),
    onSuccess: async (result) => {
      message.success(`重跑完成：${result.runId}`)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
        queryClient.invalidateQueries({ queryKey: ['runs'] }),
        queryClient.invalidateQueries({ queryKey: ['run-detail', runId] }),
        queryClient.invalidateQueries({ queryKey: ['discrepancies'] }),
      ])
    },
    onError: (error) => message.error(errorMessage(error)),
  })

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
      render: (value: string, row: ReportEntry) => formatMinor(value, row.currency),
    },
    {
      title: '金额差',
      dataIndex: 'amountMismatchMinor',
      width: 130,
      render: (value: string, row: ReportEntry) => formatMinor(value, row.currency),
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

  return (
    <Drawer
      title="运行详情"
      open={Boolean(runId)}
      onClose={onClose}
      width={screens.md ? 860 : '100%'}
      extra={
        runId && canLaunch && (
          <Popconfirm title="确认重跑当前 Run？" description="机器结果会重算，人工处置和审计会保留。" onConfirm={() => rerun.mutate()}>
            <Button icon={<ReloadOutlined />} loading={rerun.isPending}>重跑</Button>
          </Popconfirm>
        )
      }
    >
      {detail.isPending && <PageSkeleton />}
      {detail.isError && <ErrorState message={errorMessage(detail.error)} onRetry={() => void detail.refetch()} />}
      {detail.data && (
        <Space direction="vertical" size={24} style={{ width: '100%' }}>
          <Descriptions title="运行信息" bordered size="small" column={screens.md ? 2 : 1}>
            <Descriptions.Item label="Run ID"><span className="mono">{detail.data.run.runId}</span></Descriptions.Item>
            <Descriptions.Item label="状态"><RunStatusTag status={detail.data.run.status} /></Descriptions.Item>
            <Descriptions.Item label="场景">{detail.data.run.scenarioCode}</Descriptions.Item>
            <Descriptions.Item label="账期">{detail.data.run.accountingPeriod}</Descriptions.Item>
            <Descriptions.Item label="序号">#{detail.data.run.sequenceNo}</Descriptions.Item>
            <Descriptions.Item label="分桶数">{detail.data.run.bucketCount}</Descriptions.Item>
            <Descriptions.Item label="差异数">{detail.data.run.discrepancyCount}</Descriptions.Item>
            <Descriptions.Item label="待处理">{detail.data.run.openDiscrepancyCount}</Descriptions.Item>
            <Descriptions.Item label="开始时间">{formatDateTime(detail.data.run.startedAt)}</Descriptions.Item>
            <Descriptions.Item label="结束时间">{formatDateTime(detail.data.run.finishedAt)}</Descriptions.Item>
          </Descriptions>

          <section>
            <h3 className="section-title">守恒报表</h3>
            {detail.data.reports.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该 Run 尚未生成报表" />
            ) : (
              <Table
                rowKey={(row) => `${row.segmentId}:${row.currency}`}
                columns={columns}
                dataSource={detail.data.reports}
                pagination={false}
                size="small"
                scroll={{ x: 1000 }}
              />
            )}
          </section>
        </Space>
      )}
    </Drawer>
  )
}
