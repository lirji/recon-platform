import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { EyeOutlined, FilterOutlined, ReloadOutlined } from '@ant-design/icons'
import { Button, Card, Col, Form, Grid, Input, Pagination, Row, Select, Space, Table, Typography } from 'antd'
import { listDiscrepancies } from '../api/recon'
import type { DiscrepancyFilters, DiscrepancySummary } from '../api/types'
import { EmptyState, ErrorState } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { DiscrepancyTypeTag, DispositionStatusTag, discrepancyTypeLabels } from '../components/common/StatusTag'
import { DiscrepancyDetailDrawer } from '../components/discrepancies/DiscrepancyDetailDrawer'
import { colors } from '../theme/colors'
import { errorMessage, formatDateTime, formatMinor } from '../utils/format'

const typeOptions = Object.entries(discrepancyTypeLabels).map(([value, label]) => ({ value, label }))
const statusOptions = [
  ['OPEN', '待处理'],
  ['RESOLVED', '已核销'],
  ['CLOSED', '已关闭'],
  ['SUPPRESSED', '已抑制'],
  ['REOPENED', '已重开'],
  ['STALE', '已失效'],
].map(([value, label]) => ({ value, label }))
const segmentOptions = [
  { value: 'SEG1_MKT_ACCT', label: 'SEG1 营销 ↔ 账务' },
  { value: 'SEG2_ACCT_CHANNEL', label: 'SEG2 账务 ↔ 渠道' },
]

function deltaColor(value: string): string {
  try {
    return BigInt(value) === 0n ? colors.text : colors.error
  } catch {
    return colors.error
  }
}

// 从 URL query 播种初始过滤(支撑三方 roll-up 桥断下钻 /discrepancies?runId&segmentId&type=...);无 query 时行为不变。
const SEEDABLE_KEYS = ['runId', 'segmentId', 'type', 'status', 'currency', 'q'] as const

function seedFromParams(params: URLSearchParams): Partial<DiscrepancyFilters> {
  const seeded: Partial<DiscrepancyFilters> = {}
  for (const key of SEEDABLE_KEYS) {
    const value = params.get(key)
    if (value) seeded[key] = value
  }
  return seeded
}

export function DiscrepanciesPage() {
  const screens = Grid.useBreakpoint()
  const [searchParams] = useSearchParams()
  const [initialSeed] = useState(() => seedFromParams(searchParams))
  const [form] = Form.useForm<DiscrepancyFilters>()
  const [filters, setFilters] = useState<DiscrepancyFilters>(() => ({ ...initialSeed, page: 0, size: 20 }))
  const [discrepancyId, setDiscrepancyId] = useState<string | null>(null)
  const discrepancies = useQuery({
    queryKey: ['discrepancies', filters],
    queryFn: () => listDiscrepancies(filters),
  })

  const applyFilters = (values: DiscrepancyFilters) => setFilters({ ...values, page: 0, size: filters.size || 20 })
  const resetFilters = () => {
    form.resetFields()
    setFilters({ page: 0, size: filters.size || 20 })
  }
  const hasFilters = Object.entries(filters).some(([key, value]) => !['page', 'size'].includes(key) && Boolean(value))

  const columns = [
    {
      title: '差异类型',
      dataIndex: 'type',
      width: 150,
      render: (type: string) => <DiscrepancyTypeTag type={type} />,
    },
    {
      title: '业务键',
      width: 220,
      render: (_: unknown, row: DiscrepancySummary) => (
        <button className="cell-link" onClick={() => setDiscrepancyId(row.discrepancyId)}>
          <strong>{row.groupKey || row.matchKey || '无业务键'}</strong>
          <small className="mono">{row.matchKey || row.discrepancyId}</small>
        </button>
      ),
    },
    { title: '分段', dataIndex: 'segmentId', width: 180 },
    {
      title: '差额（最小单位）',
      dataIndex: 'deltaAmountMinor',
      width: 170,
      render: (value: string, row: DiscrepancySummary) => <Typography.Text strong style={{ color: deltaColor(value) }}>{formatMinor(value, row.currency)}</Typography.Text>,
    },
    { title: '处置状态', dataIndex: 'dispositionStatus', width: 110, render: (status: string) => <DispositionStatusTag status={status} /> },
    { title: '操作人', dataIndex: 'operator', width: 100, render: (value: string | null) => value || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', width: 180, render: formatDateTime },
    { title: '操作', fixed: 'right' as const, width: 80, render: (_: unknown, row: DiscrepancySummary) => <Button type="link" icon={<EyeOutlined />} onClick={() => setDiscrepancyId(row.discrepancyId)}>详情</Button> },
  ]

  return (
    <>
      <PageHeader
        eyebrow="DISCREPANCY WORKBENCH"
        title="差异处理"
        description="从机器差异定位到原始血缘，查看跨重跑处置状态和审计，并执行人工核销或关闭。"
      />

      <Card className="filter-card">
        <Form<DiscrepancyFilters> form={form} layout="vertical" initialValues={initialSeed} onFinish={applyFilters}>
          <Row gutter={12}>
            <Col xs={24} md={12} xl={6}>
              <Form.Item name="q" label="关键字">
                <Input allowClear placeholder="业务键 / 指纹 / 血缘" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6} xl={4}>
              <Form.Item name="status" label="处置状态">
                <Select allowClear placeholder="全部" options={statusOptions} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6} xl={5}>
              <Form.Item name="type" label="差异类型">
                <Select allowClear showSearch optionFilterProp="label" placeholder="全部" options={typeOptions} />
              </Form.Item>
            </Col>
            <Col xs={12} md={8} xl={5}>
              <Form.Item name="segmentId" label="对账分段">
                <Select allowClear placeholder="全部" options={segmentOptions} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4} xl={2}>
              <Form.Item name="currency" label="币种">
                <Input allowClear maxLength={3} placeholder="USD" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12} xl={6}>
              <Form.Item name="runId" label="Run ID">
                <Input allowClear placeholder="精确匹配 Run" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12} xl={6} className="filter-button-col">
              <Form.Item label=" ">
                <Space wrap>
                  <Button type="primary" htmlType="submit" icon={<FilterOutlined />}>筛选</Button>
                  <Button onClick={resetFilters}>重置</Button>
                  <Button icon={<ReloadOutlined />} onClick={() => void discrepancies.refetch()}>刷新</Button>
                </Space>
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Card>

      <Card className="data-card">
        {discrepancies.isError && <ErrorState message={errorMessage(discrepancies.error)} onRetry={() => void discrepancies.refetch()} />}
        {!discrepancies.isError && screens.md && (
          <Table<DiscrepancySummary>
            rowKey="discrepancyId"
            columns={columns}
            dataSource={discrepancies.data?.content || []}
            loading={discrepancies.isPending || discrepancies.isFetching}
            pagination={false}
            scroll={{ x: 1250 }}
            locale={{ emptyText: <EmptyState filtered={hasFilters} onReset={resetFilters} /> }}
          />
        )}
        {!discrepancies.isError && !screens.md && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {(discrepancies.data?.content || []).map((item) => (
              <button className="mobile-data-card" key={item.discrepancyId} onClick={() => setDiscrepancyId(item.discrepancyId)}>
                <span className="mobile-card-heading"><DiscrepancyTypeTag type={item.type} /><DispositionStatusTag status={item.dispositionStatus} /></span>
                <strong>{item.groupKey || item.matchKey || '无业务键'}</strong>
                <span className="mono mobile-card-id">{item.runId}</span>
                <span className="mobile-card-stats"><span>{item.segmentId}</span><span style={{ color: deltaColor(item.deltaAmountMinor) }}>{formatMinor(item.deltaAmountMinor, item.currency)}</span><span>{formatDateTime(item.updatedAt)}</span></span>
              </button>
            ))}
            {!discrepancies.isPending && discrepancies.data?.content.length === 0 && <EmptyState filtered={hasFilters} onReset={resetFilters} />}
          </Space>
        )}
        {discrepancies.data && discrepancies.data.totalElements > 0 && (
          <Row justify="end" className="pagination-row">
            <Pagination
              current={discrepancies.data.page + 1}
              pageSize={discrepancies.data.size}
              total={discrepancies.data.totalElements}
              showSizeChanger
              showTotal={(total) => `共 ${total} 条`}
              onChange={(page, size) => setFilters((current) => ({ ...current, page: page - 1, size }))}
            />
          </Row>
        )}
      </Card>

      <DiscrepancyDetailDrawer discrepancyId={discrepancyId} onClose={() => setDiscrepancyId(null)} />
    </>
  )
}
