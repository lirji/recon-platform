import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { EyeOutlined, FilterOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { Button, Card, Col, Form, Grid, Input, Pagination, Row, Select, Space, Table, Typography } from 'antd'
import { listRuns } from '../api/recon'
import type { RunFilters, RunSummary } from '../api/types'
import { EmptyState, ErrorState } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { RunStatusTag } from '../components/common/StatusTag'
import { LaunchRunModal } from '../components/runs/LaunchRunModal'
import { RunDetailDrawer } from '../components/runs/RunDetailDrawer'
import { useAuth } from '../auth/AuthContext'
import { errorMessage, formatDateTime } from '../utils/format'

const statusOptions = [
  ['CREATED', '已创建'],
  ['LOADING', '装载中'],
  ['MATCHING', '匹配中'],
  ['COMPLETED', '已完成'],
  ['REPORT_IMBALANCE', '守恒异常'],
  ['FAILED', '执行失败'],
].map(([value, label]) => ({ value, label }))

export function RunsPage() {
  const screens = Grid.useBreakpoint()
  const [form] = Form.useForm<RunFilters>()
  const [filters, setFilters] = useState<RunFilters>({ page: 0, size: 20 })
  const [launchOpen, setLaunchOpen] = useState(false)
  const canLaunch = useAuth().can('recon.launch')
  const [runId, setRunId] = useState<string | null>(null)
  const runs = useQuery({
    queryKey: ['runs', filters],
    queryFn: () => listRuns(filters),
    refetchInterval: 20_000,
    refetchIntervalInBackground: false,
  })

  const applyFilters = (values: RunFilters) => setFilters({ ...values, page: 0, size: filters.size || 20 })
  const resetFilters = () => {
    form.resetFields()
    setFilters({ page: 0, size: filters.size || 20 })
  }

  const columns = [
    {
      title: 'Run ID',
      dataIndex: 'runId',
      width: 290,
      render: (value: string) => <Button type="link" className="table-link" onClick={() => setRunId(value)}>{value}</Button>,
    },
    { title: '账期', dataIndex: 'accountingPeriod', width: 120 },
    { title: '序号', dataIndex: 'sequenceNo', width: 70, render: (value: number) => `#${value}` },
    { title: '状态', dataIndex: 'status', width: 110, render: (status: string) => <RunStatusTag status={status} /> },
    { title: '差异', dataIndex: 'discrepancyCount', width: 80 },
    { title: '待处理', dataIndex: 'openDiscrepancyCount', width: 90 },
    {
      title: '守恒',
      dataIndex: 'balanced',
      width: 90,
      render: (value: boolean | null) => value == null ? '待生成' : <Typography.Text type={value ? 'success' : 'danger'}>{value ? '通过' : '异常'}</Typography.Text>,
    },
    { title: '开始时间', dataIndex: 'startedAt', width: 180, render: formatDateTime },
    { title: '操作', fixed: 'right' as const, width: 80, render: (_: unknown, row: RunSummary) => <Button type="link" icon={<EyeOutlined />} onClick={() => setRunId(row.runId)}>详情</Button> },
  ]

  return (
    <>
      <PageHeader
        eyebrow="RUN OPERATIONS"
        title="运行管理"
        description="按账期和状态追踪每次对账运行，查看守恒报表，并在保留人工痕迹的前提下安全重跑。"
        extra={canLaunch ? <Button type="primary" icon={<PlusOutlined />} onClick={() => setLaunchOpen(true)}>发起对账</Button> : undefined}
      />

      <Card className="filter-card">
        <Form<RunFilters> form={form} layout={screens.lg ? 'inline' : 'vertical'} onFinish={applyFilters}>
          <Form.Item name="scenarioCode" label="场景">
            <Select allowClear placeholder="全部场景" style={{ minWidth: 180 }} options={[{ value: 'MARKETING_3WAY', label: '营销三方对账' }]} />
          </Form.Item>
          <Form.Item name="accountingPeriod" label="账期">
            <Input type="date" style={{ minWidth: 160 }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear placeholder="全部状态" style={{ minWidth: 150 }} options={statusOptions} />
          </Form.Item>
          <Form.Item className="filter-actions">
            <Space>
              <Button type="primary" htmlType="submit" icon={<FilterOutlined />}>筛选</Button>
              <Button onClick={resetFilters}>重置</Button>
              <Button icon={<ReloadOutlined />} onClick={() => void runs.refetch()}>刷新</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card className="data-card">
        {runs.isError && <ErrorState message={errorMessage(runs.error)} onRetry={() => void runs.refetch()} />}
        {!runs.isError && screens.md && (
          <Table<RunSummary>
            rowKey="runId"
            columns={columns}
            dataSource={runs.data?.content || []}
            loading={runs.isPending || runs.isFetching}
            pagination={false}
            scroll={{ x: 1200 }}
            locale={{ emptyText: <EmptyState filtered={Object.keys(filters).some((key) => !['page', 'size'].includes(key))} onReset={resetFilters} /> }}
          />
        )}
        {!runs.isError && !screens.md && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {(runs.data?.content || []).map((run) => (
              <button className="mobile-data-card" key={run.runId} onClick={() => setRunId(run.runId)}>
                <span className="mobile-card-heading"><strong>{run.accountingPeriod} · #{run.sequenceNo}</strong><RunStatusTag status={run.status} /></span>
                <span className="mono mobile-card-id">{run.runId}</span>
                <span className="mobile-card-stats"><span>差异 {run.discrepancyCount}</span><span>待处理 {run.openDiscrepancyCount}</span><span>{formatDateTime(run.startedAt)}</span></span>
              </button>
            ))}
            {!runs.isPending && runs.data?.content.length === 0 && <EmptyState filtered onReset={resetFilters} />}
          </Space>
        )}
        {runs.data && runs.data.totalElements > 0 && (
          <Row justify="end" className="pagination-row">
            <Col>
              <Pagination
                current={runs.data.page + 1}
                pageSize={runs.data.size}
                total={runs.data.totalElements}
                showSizeChanger
                showTotal={(total) => `共 ${total} 条`}
                onChange={(page, size) => setFilters((current) => ({ ...current, page: page - 1, size }))}
              />
            </Col>
          </Row>
        )}
      </Card>

      <LaunchRunModal open={launchOpen} onClose={() => setLaunchOpen(false)} onLaunched={setRunId} />
      <RunDetailDrawer runId={runId} onClose={() => setRunId(null)} />
    </>
  )
}
