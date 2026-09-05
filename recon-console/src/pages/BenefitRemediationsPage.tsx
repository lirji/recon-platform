import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircleOutlined, PlusOutlined, StopOutlined } from '@ant-design/icons'
import { App, Alert, Button, Card, Form, Grid, Input, Modal, Pagination, Select, Space, Table, Typography } from 'antd'
import { Link } from 'react-router-dom'
import { approveRemediation, listRemediations, rejectRemediation } from '../api/recon'
import type { RemediationView } from '../api/types'
import { AUTH_CONFIG } from '../auth/config'
import { useAuth } from '../auth/AuthContext'
import { EmptyState, ErrorState } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { RemediationActionTag, RemediationStatusTag } from '../components/common/StatusTag'
import { ProposeRemediationModal } from '../components/remediations/ProposeRemediationModal'
import { errorMessage, formatDateTime } from '../utils/format'
import { pickSearchParams, toSearchParams } from '../utils/searchParams'

const STATUS_OPTIONS = [
  ['PROPOSED', '待审批'],
  ['APPROVED', '已批准'],
  ['REJECTED', '已驳回'],
  ['DISPATCHING', '派发中'],
  ['SUCCEEDED', '已成功'],
  ['FAILED', '已失败'],
  ['UNKNOWN', '未知'],
].map(([value, label]) => ({ value, label }))

interface Filters {
  tenantId?: string
  status?: string
}

interface Decision {
  row: RemediationView
  approved: boolean
}

export function BenefitRemediationsPage() {
  const screens = Grid.useBreakpoint()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const canDispose = useAuth().can('recon.dispose')
  const [searchParams, setSearchParams] = useSearchParams()
  const seeded = pickSearchParams(searchParams, ['tenantId', 'status'])
  const [form] = Form.useForm<Filters>()
  const [pageState, setPageState] = useState({ page: 0, size: 20 })
  const [proposeOpen, setProposeOpen] = useState(false)
  const [decision, setDecision] = useState<Decision | null>(null)
  const [approvalRef, setApprovalRef] = useState('')

  const tenantId = seeded.tenantId || AUTH_CONFIG.organization
  const filters = { tenantId, status: seeded.status, ...pageState }

  useEffect(() => {
    form.setFieldsValue({ tenantId, status: seeded.status })
  }, [form, tenantId, seeded.status])

  const remediations = useQuery({
    queryKey: ['benefit-remediations', filters],
    queryFn: () => listRemediations(filters),
  })
  const rows = remediations.data?.content || []

  const applyFilters = (values: Filters) => {
    setPageState((current) => ({ page: 0, size: current.size }))
    setSearchParams(toSearchParams({
      tenantId: values.tenantId || AUTH_CONFIG.organization,
      status: values.status,
    }), { replace: true })
  }

  const decide = useMutation({
    mutationFn: () => {
      const body = { tenantId, approvalRef: approvalRef.trim() }
      return decision!.approved
        ? approveRemediation(decision!.row.suggestionId, body)
        : rejectRemediation(decision!.row.suggestionId, body)
    },
    onSuccess: async () => {
      message.success(decision?.approved ? '已批准补救建议' : '已驳回补救建议')
      setDecision(null)
      setApprovalRef('')
      await queryClient.invalidateQueries({ queryKey: ['benefit-remediations'] })
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const actionButtons = (row: RemediationView) => {
    if (!canDispose || row.status !== 'PROPOSED') {
      return <Typography.Text type="secondary">—</Typography.Text>
    }
    return (
      <Space>
        {row.action !== 'MANUAL_REVIEW' && (
          <Button type="link" icon={<CheckCircleOutlined />} onClick={() => { setApprovalRef(''); setDecision({ row, approved: true }) }}>
            批准
          </Button>
        )}
        <Button type="link" danger icon={<StopOutlined />} onClick={() => { setApprovalRef(''); setDecision({ row, approved: false }) }}>
          驳回
        </Button>
      </Space>
    )
  }

  const columns = [
    {
      title: '建议',
      dataIndex: 'suggestionId',
      width: 220,
      render: (value: string) => <span className="mono">{value}</span>,
    },
    { title: '动作', dataIndex: 'action', width: 110, render: (value: string) => <RemediationActionTag action={value} /> },
    { title: '状态', dataIndex: 'status', width: 110, render: (value: string) => <RemediationStatusTag status={value} /> },
    {
      title: '差异',
      dataIndex: 'discrepancyRef',
      width: 180,
      render: (value: string) => (
        <Link to={`/discrepancies?q=${encodeURIComponent(value)}`} className="mono">{value}</Link>
      ),
    },
    { title: '发放项', dataIndex: 'awardItemNo', width: 140, render: (value: string) => <span className="mono">{value}</span> },
    { title: '场景', dataIndex: 'scenarioCode', width: 180, render: (value: string) => <span className="mono">{value}</span> },
    { title: '原因', dataIndex: 'reason', ellipsis: true },
    { title: '更新时间', dataIndex: 'updatedAt', width: 180, render: formatDateTime },
    { title: '操作', fixed: 'right' as const, width: 160, render: (_: unknown, row: RemediationView) => actionButtons(row) },
  ]

  return (
    <>
      <PageHeader
        eyebrow="BENEFIT REMEDIATION"
        title="权益补救"
        description="提出并审批权益补救建议。批准只写 outbox；relay 默认关闭，不会自动闭环差异或动资金。"
        extra={
          canDispose ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setProposeOpen(true)}>提出建议</Button>
          ) : undefined
        }
      />

      <Card className="data-card">
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="这不是全自动纠错台。BENEFIT_CASH_3WAY 种子默认停用；中台结果回写与 relay 需单独打开。"
        />
        <Form<Filters> form={form} layout="inline" onFinish={applyFilters} style={{ marginBottom: 16, rowGap: 12 }}>
          <Form.Item name="tenantId" label="租户">
            <Input className="mono" style={{ minWidth: 200 }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear placeholder="全部状态" style={{ minWidth: 140 }} options={STATUS_OPTIONS} />
          </Form.Item>
          <Button type="primary" htmlType="submit">筛选</Button>
        </Form>

        {remediations.isError && (
          <ErrorState message={errorMessage(remediations.error)} onRetry={() => void remediations.refetch()} />
        )}

        {!remediations.isError && screens.md && (
          <Table<RemediationView>
            rowKey="suggestionId"
            columns={columns}
            dataSource={rows}
            loading={remediations.isPending || remediations.isFetching}
            pagination={false}
            scroll={{ x: 1400 }}
            locale={{ emptyText: <EmptyState /> }}
          />
        )}

        {!remediations.isError && !screens.md && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {rows.map((row) => (
              <div className="mobile-data-card" key={row.suggestionId}>
                <span className="mobile-card-heading">
                  <strong>{row.awardItemNo}</strong>
                  <RemediationStatusTag status={row.status} />
                </span>
                <span className="mono mobile-card-id">{row.suggestionId}</span>
                <span className="mobile-card-stats">
                  <RemediationActionTag action={row.action} />
                  <span className="mono">{row.discrepancyRef}</span>
                  <span>{formatDateTime(row.updatedAt)}</span>
                </span>
                <div className="mobile-card-actions">{actionButtons(row)}</div>
              </div>
            ))}
            {!remediations.isPending && rows.length === 0 && <EmptyState />}
          </Space>
        )}

        {remediations.data && remediations.data.totalElements > 0 && (
          <Pagination
            style={{ marginTop: 16 }}
            current={remediations.data.page + 1}
            pageSize={remediations.data.size}
            total={remediations.data.totalElements}
            showSizeChanger
            onChange={(page, size) => setPageState({ page: page - 1, size })}
          />
        )}
      </Card>

      <ProposeRemediationModal open={proposeOpen} onClose={() => setProposeOpen(false)} />

      <Modal
        title={decision?.approved ? '批准补救' : '驳回补救'}
        open={Boolean(decision)}
        onCancel={() => setDecision(null)}
        onOk={() => decide.mutate()}
        okText={decision?.approved ? '确认批准' : '确认驳回'}
        okButtonProps={{ danger: decision ? !decision.approved : false, disabled: !approvalRef.trim() }}
        confirmLoading={decide.isPending}
        destroyOnHidden
      >
        <Alert
          type={decision?.approved ? 'warning' : 'info'}
          showIcon
          style={{ marginBottom: 16 }}
          message={
            decision?.approved
              ? '批准会写入 command outbox。默认 relay 关闭，不会对中台发指令，更不会自动核销差异。'
              : '驳回后建议停留在 REJECTED，可再另提一条。'
          }
        />
        <Input
          aria-label="审批引用"
          placeholder="审批引用（必填）"
          value={approvalRef}
          onChange={(e) => setApprovalRef(e.target.value)}
        />
      </Modal>
    </>
  )
}
