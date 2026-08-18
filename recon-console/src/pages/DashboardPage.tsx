import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AlertOutlined, AuditOutlined, CheckCircleOutlined, ClockCircleOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Card, Col, Grid, Row, Space, Table, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { getDashboard } from '../api/recon'
import type { RunSummary } from '../api/types'
import { ErrorState, PageSkeleton } from '../components/common/AsyncState'
import { MetricCard } from '../components/common/MetricCard'
import { PageHeader } from '../components/common/PageHeader'
import { RunStatusTag } from '../components/common/StatusTag'
import { DiscrepancyPieChart } from '../components/dashboard/DiscrepancyPieChart'
import { LaunchRunModal } from '../components/runs/LaunchRunModal'
import { RunDetailDrawer } from '../components/runs/RunDetailDrawer'
import { errorMessage, formatCount, formatDateTime } from '../utils/format'

export function DashboardPage() {
  const navigate = useNavigate()
  const screens = Grid.useBreakpoint()
  const [launchOpen, setLaunchOpen] = useState(false)
  const [runId, setRunId] = useState<string | null>(null)
  const dashboard = useQuery({
    queryKey: ['dashboard'],
    queryFn: getDashboard,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
  })

  if (dashboard.isPending) return <PageSkeleton />
  if (dashboard.isError) return <ErrorState message={errorMessage(dashboard.error)} onRetry={() => void dashboard.refetch()} />

  const { metrics, discrepancyTypes, recentRuns } = dashboard.data
  const columns = [
    {
      title: 'Run ID',
      dataIndex: 'runId',
      render: (value: string) => <Button type="link" className="table-link" onClick={() => setRunId(value)}>{value}</Button>,
    },
    { title: '账期', dataIndex: 'accountingPeriod', width: 120 },
    { title: '状态', dataIndex: 'status', width: 110, render: (status: string) => <RunStatusTag status={status} /> },
    { title: '差异', dataIndex: 'discrepancyCount', width: 90 },
    { title: '待处理', dataIndex: 'openDiscrepancyCount', width: 90 },
    { title: '开始时间', dataIndex: 'startedAt', width: 180, render: formatDateTime },
  ]

  return (
    <>
      <PageHeader
        eyebrow="RECONCILIATION OPERATIONS"
        title="对账运营总览"
        description="先看异常是否需要处理，再进入运行和差异明细。数据每 30 秒自动刷新。"
        extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => setLaunchOpen(true)}>发起对账</Button>}
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard label="累计运行" value={formatCount(metrics.totalRuns)} hint={`${metrics.completedRuns} 次正常完成`} icon={<AuditOutlined />} tone="primary" actionLabel="查看运行" onAction={() => navigate('/runs')} />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard label="进行中" value={formatCount(metrics.runningRuns)} hint="装载与匹配中的任务" icon={<ClockCircleOutlined />} tone="warning" />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard label="失败 / 守恒异常" value={formatCount(metrics.failedRuns + metrics.imbalancedRuns)} hint={`${metrics.failedRuns} 失败 · ${metrics.imbalancedRuns} 不平衡`} icon={<AlertOutlined />} tone="error" actionLabel="立即排查" onAction={() => navigate('/runs')} />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard label="待处理差异" value={formatCount(metrics.openDiscrepancies)} hint={`${metrics.resolvedDiscrepancies} 已核销 · ${metrics.closedDiscrepancies} 已关闭`} icon={<CheckCircleOutlined />} tone="success" actionLabel="处理差异" onAction={() => navigate('/discrepancies')} />
        </Col>
      </Row>

      <Row gutter={[16, 16]} className="dashboard-grid">
        <Col xs={24} xl={9}>
          <Card title="差异构成" extra={<Typography.Text type="secondary">历史机器结果</Typography.Text>}>
            {discrepancyTypes.length === 0 ? (
              <div className="chart-empty">尚无差异数据</div>
            ) : (
              <DiscrepancyPieChart data={discrepancyTypes} />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={15}>
          <Card
            title="最近运行"
            extra={<Button type="link" onClick={() => navigate('/runs')}>全部运行</Button>}
          >
            {screens.md ? (
              <Table<RunSummary> rowKey="runId" columns={columns} dataSource={recentRuns} pagination={false} size="small" scroll={{ x: 760 }} />
            ) : (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {recentRuns.map((run) => (
                  <button className="mobile-record" key={run.runId} onClick={() => setRunId(run.runId)}>
                    <span><strong>{run.accountingPeriod}</strong><small>{run.runId}</small></span>
                    <RunStatusTag status={run.status} />
                  </button>
                ))}
                {recentRuns.length === 0 && <div className="chart-empty">尚无运行记录</div>}
              </Space>
            )}
          </Card>
        </Col>
      </Row>

      <LaunchRunModal open={launchOpen} onClose={() => setLaunchOpen(false)} onLaunched={setRunId} />
      <RunDetailDrawer runId={runId} onClose={() => setRunId(null)} />
    </>
  )
}
