import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircleOutlined, StopOutlined } from '@ant-design/icons'
import { App, Alert, Button, Card, Form, Grid, Input, Modal, Space, Table, Typography } from 'antd'
import { ApiError } from '../api/client'
import { decideReversalApproval, listReversalApprovals } from '../api/recon'
import type { PendingApprovalView } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { EmptyState, ErrorState } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { ReversalStatusTag } from '../components/common/StatusTag'
import { errorMessage, formatDateTime, formatMinor } from '../utils/format'

interface Decision {
  task: PendingApprovalView
  approved: boolean
}

interface DecisionValues {
  note?: string
}

// 金额可能因 join miss 为 null(formatMinor 不接受 null)—— 显式兜底为「—」。
function renderAmount(row: PendingApprovalView) {
  return row.suggestedAmountMinor == null ? '—' : formatMinor(row.suggestedAmountMinor, row.currency)
}

export function ReversalApprovalsPage() {
  const screens = Grid.useBreakpoint()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const auth = useAuth()
  // recon.dispose 才能提交/审批(观察员只读);后端授权仍是安全边界。
  const canDispose = auth.can('recon.dispose')
  const [decision, setDecision] = useState<Decision | null>(null)
  const [form] = Form.useForm<DecisionValues>()

  const approvals = useQuery({ queryKey: ['reversal-approvals'], queryFn: listReversalApprovals })
  const rows = approvals.data || []

  const openDecision = (task: PendingApprovalView, approved: boolean) => {
    form.setFieldsValue({ note: '' })
    setDecision({ task, approved })
  }

  const mutation = useMutation({
    mutationFn: (values: DecisionValues) =>
      decideReversalApproval(decision!.task.taskId, decision!.approved, auth.user?.name, values.note?.trim()),
    onSuccess: async () => {
      message.success(decision?.approved ? '已通过审批' : '已驳回审批')
      setDecision(null)
      form.resetFields()
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['reversal-approvals'] }),
        queryClient.invalidateQueries({ queryKey: ['discrepancies'] }),
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      ])
    },
    onError: async (error) => {
      // 400 bad_request: 该 taskId 已被他人审批(task 已消失)—— 友好提示并刷新列表。
      if (error instanceof ApiError && error.status === 400) {
        message.warning('该审批任务已被处理，已为你刷新列表')
        setDecision(null)
        await queryClient.invalidateQueries({ queryKey: ['reversal-approvals'] })
      } else {
        message.error(errorMessage(error))
      }
    },
  })

  // 工作流未启用时后端返 409 illegal_transition —— 专用整页错误文案,勿混同版本冲突。
  const workflowDisabled = approvals.error instanceof ApiError && approvals.error.code === 'illegal_transition'

  const actionButtons = (row: PendingApprovalView) =>
    canDispose ? (
      <Space>
        <Button type="link" icon={<CheckCircleOutlined />} onClick={() => openDecision(row, true)}>
          通过
        </Button>
        <Button type="link" danger icon={<StopOutlined />} onClick={() => openDecision(row, false)}>
          驳回
        </Button>
      </Space>
    ) : (
      <Typography.Text type="secondary">仅可查看</Typography.Text>
    )

  const columns = [
    {
      title: '冲正建议',
      dataIndex: 'reversalId',
      width: 220,
      render: (value: string | null) => <span className="mono">{value || '—'}</span>,
    },
    { title: '建议金额', width: 160, render: (_: unknown, row: PendingApprovalView) => renderAmount(row) },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: string | null) => (value ? <ReversalStatusTag status={value} /> : '—'),
    },
    { title: '提交时间', dataIndex: 'createdAt', width: 180, render: formatDateTime },
    {
      title: '操作',
      fixed: 'right' as const,
      width: 180,
      render: (_: unknown, row: PendingApprovalView) => actionButtons(row),
    },
  ]

  return (
    <>
      <PageHeader
        eyebrow="REVERSAL APPROVAL"
        title="冲正审批"
        description="审批待处理的冲正建议(通过 → 进入执行流 / 驳回 → 作废)。审批需填写意见,操作留痕。"
      />

      <Card className="data-card">
        {approvals.isError && (
          <ErrorState
            message={
              workflowDisabled
                ? '审批工作流未启用,请联系管理员开启 recon.workflow.flowable.enabled 后重试。'
                : errorMessage(approvals.error)
            }
            onRetry={() => void approvals.refetch()}
          />
        )}

        {!approvals.isError && screens.md && (
          <Table<PendingApprovalView>
            rowKey="taskId"
            columns={columns}
            dataSource={rows}
            loading={approvals.isPending || approvals.isFetching}
            pagination={false}
            scroll={{ x: 900 }}
            locale={{ emptyText: <EmptyState /> }}
          />
        )}

        {!approvals.isError && !screens.md && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {rows.map((row) => (
              <div className="mobile-data-card" key={row.taskId}>
                <span className="mobile-card-heading">
                  <strong>{renderAmount(row)}</strong>
                  {row.status ? <ReversalStatusTag status={row.status} /> : null}
                </span>
                <span className="mobile-card-stats">
                  <span className="mono">{row.reversalId || '—'}</span>
                  <span>{formatDateTime(row.createdAt)}</span>
                </span>
                <div className="mobile-card-actions">{actionButtons(row)}</div>
              </div>
            ))}
            {!approvals.isPending && rows.length === 0 && <EmptyState />}
          </Space>
        )}
      </Card>

      <Modal
        title={decision?.approved ? '通过审批' : '驳回审批'}
        open={Boolean(decision)}
        onCancel={() => setDecision(null)}
        onOk={() => form.submit()}
        okText={decision?.approved ? '确认通过' : '确认驳回'}
        okButtonProps={{ danger: decision ? !decision.approved : false }}
        confirmLoading={mutation.isPending}
        destroyOnHidden
      >
        <Form<DecisionValues> form={form} layout="vertical" onFinish={(values) => mutation.mutate(values)}>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={
              <>
                审批人:<b>{auth.user?.name || '当前用户'}</b>(取自登录身份);冲正建议{' '}
                <span className="mono">{decision?.task.reversalId || '—'}</span>,金额{' '}
                {decision ? renderAmount(decision.task) : '—'}
              </>
            }
          />
          <Form.Item
            name="note"
            label="审批意见"
            rules={[
              { required: true, message: '请填写审批意见' },
              { max: 512, message: '意见不能超过 512 字符' },
            ]}
          >
            <Input.TextArea rows={4} showCount maxLength={512} placeholder="记录通过依据或驳回原因(必填)" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
