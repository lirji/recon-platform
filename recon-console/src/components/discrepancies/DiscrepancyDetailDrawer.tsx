import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { closeDiscrepancy, getDiscrepancy, resolveDiscrepancy } from '../../api/recon'
import type { ClearRequest, DiscrepancySummary } from '../../api/types'
import { App, Button, Collapse, Descriptions, Divider, Drawer, Empty, Form, Grid, Input, Modal, Space, Table, Timeline, Typography } from 'antd'
import { CheckCircleOutlined, StopOutlined } from '@ant-design/icons'
import { ErrorState, PageSkeleton } from '../common/AsyncState'
import { DiscrepancyTypeTag, DispositionStatusTag } from '../common/StatusTag'
import { errorMessage, formatDateTime, formatMinor } from '../../utils/format'

type Action = 'resolve' | 'close'

interface ActionValues {
  operator: string
  note?: string
}

interface Props {
  discrepancyId: string | null
  onClose: () => void
}

function allowedActions(discrepancy: DiscrepancySummary): Action[] {
  if (['CLOSED'].includes(discrepancy.dispositionStatus)) return []
  if (discrepancy.dispositionStatus === 'RESOLVED' || discrepancy.dispositionStatus === 'SUPPRESSED') return ['close']
  return ['resolve', 'close']
}

export function DiscrepancyDetailDrawer({ discrepancyId, onClose }: Props) {
  const screens = Grid.useBreakpoint()
  const [action, setAction] = useState<Action | null>(null)
  const [form] = Form.useForm<ActionValues>()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const detail = useQuery({
    queryKey: ['discrepancy-detail', discrepancyId],
    queryFn: () => getDiscrepancy(discrepancyId!),
    enabled: Boolean(discrepancyId),
  })

  const mutation = useMutation({
    mutationFn: async (values: ActionValues) => {
      const request: ClearRequest = {
        operator: values.operator.trim(),
        note: values.note?.trim() || undefined,
        expectedVersion: detail.data?.discrepancy.dispositionVersion ?? undefined,
      }
      if (action === 'resolve') return resolveDiscrepancy(discrepancyId!, request)
      return closeDiscrepancy(discrepancyId!, request)
    },
    onSuccess: async (result, values) => {
      sessionStorage.setItem('recon-console.operator', values.operator.trim())
      message.success(result.status === 'RESOLVED' ? '差异已核销' : '差异已关闭')
      setAction(null)
      form.resetFields()
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
        queryClient.invalidateQueries({ queryKey: ['runs'] }),
        queryClient.invalidateQueries({ queryKey: ['discrepancies'] }),
        queryClient.invalidateQueries({ queryKey: ['discrepancy-detail', discrepancyId] }),
      ])
    },
    onError: async (error) => {
      if (error instanceof ApiError && error.status === 409) {
        message.warning('处置状态已被其他操作更新，已为你刷新详情')
        await queryClient.invalidateQueries({ queryKey: ['discrepancy-detail', discrepancyId] })
      } else {
        message.error(errorMessage(error))
      }
    },
  })

  const openAction = (next: Action) => {
    form.setFieldsValue({ operator: sessionStorage.getItem('recon-console.operator') || '', note: '' })
    setAction(next)
  }

  const discrepancy = detail.data?.discrepancy
  const actions = discrepancy ? allowedActions(discrepancy) : []

  return (
    <>
      <Drawer
        title="差异详情"
        open={Boolean(discrepancyId)}
        onClose={onClose}
        width={screens.md ? 900 : '100%'}
        extra={
          discrepancy && (
            <Space>
              {actions.includes('resolve') && <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => openAction('resolve')}>核销</Button>}
              {actions.includes('close') && <Button icon={<StopOutlined />} onClick={() => openAction('close')}>关闭</Button>}
            </Space>
          )
        }
      >
        {detail.isPending && <PageSkeleton />}
        {detail.isError && <ErrorState message={errorMessage(detail.error)} onRetry={() => void detail.refetch()} />}
        {detail.data && (
          <Space direction="vertical" size={20} style={{ width: '100%' }}>
            <div className="detail-hero">
              <div>
                <Space wrap><DiscrepancyTypeTag type={detail.data.discrepancy.type} /><DispositionStatusTag status={detail.data.discrepancy.dispositionStatus} /></Space>
                <h2>{detail.data.discrepancy.groupKey || detail.data.discrepancy.matchKey || '无业务键差异'}</h2>
                <span className="mono">{detail.data.discrepancy.discrepancyId}</span>
              </div>
              <div className="detail-amount">
                <span>差额（最小单位）</span>
                <strong>{formatMinor(detail.data.discrepancy.deltaAmountMinor, detail.data.discrepancy.currency)}</strong>
              </div>
            </div>

            <Descriptions bordered size="small" column={screens.md ? 2 : 1}>
              <Descriptions.Item label="Run ID"><span className="mono">{detail.data.discrepancy.runId}</span></Descriptions.Item>
              <Descriptions.Item label="分段">{detail.data.discrepancy.segmentId}</Descriptions.Item>
              <Descriptions.Item label="应对金额">{formatMinor(detail.data.discrepancy.expectedAmountMinor, detail.data.discrepancy.currency)}</Descriptions.Item>
              <Descriptions.Item label="实际金额">{formatMinor(detail.data.discrepancy.actualAmountMinor, detail.data.discrepancy.currency)}</Descriptions.Item>
              <Descriptions.Item label="匹配键"><span className="mono">{detail.data.discrepancy.matchKey || '—'}</span></Descriptions.Item>
              <Descriptions.Item label="分组键"><span className="mono">{detail.data.discrepancy.groupKey || '—'}</span></Descriptions.Item>
              <Descriptions.Item label="左侧血缘"><span className="mono">{detail.data.discrepancy.leftRawRef || '—'}</span></Descriptions.Item>
              <Descriptions.Item label="右侧血缘"><span className="mono">{detail.data.discrepancy.rightRawRef || '—'}</span></Descriptions.Item>
              <Descriptions.Item label="处置人">{detail.data.discrepancy.operator || '—'}</Descriptions.Item>
              <Descriptions.Item label="处置版本">{detail.data.discrepancy.dispositionVersion ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="处置备注" span={screens.md ? 2 : 1}>{detail.data.discrepancy.note || '—'}</Descriptions.Item>
              <Descriptions.Item label="Fingerprint" span={screens.md ? 2 : 1}><span className="mono">{detail.data.discrepancy.fingerprint}</span></Descriptions.Item>
            </Descriptions>

            <Divider orientation="left">处理审计</Divider>
            {detail.data.actions.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无处理审计" />
            ) : (
              <Timeline
                items={detail.data.actions.map((item) => ({
                  color: item.actionType.startsWith('MANUAL_') ? 'blue' : 'gray',
                  children: (
                    <div className="timeline-item">
                      <strong>{item.actionType}</strong>
                      <span>{item.operator} · {formatDateTime(item.createdAt)}</span>
                      {item.payload && <Typography.Paragraph type="secondary" ellipsis={{ rows: 2, expandable: true }}>{item.payload}</Typography.Paragraph>}
                    </div>
                  ),
                }))}
              />
            )}

            <Collapse
              items={[
                {
                  key: 'reversals',
                  label: `冲正建议（${detail.data.reversals.length}）`,
                  children: detail.data.reversals.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无冲正建议" /> : (
                    <Table
                      rowKey="id"
                      size="small"
                      pagination={false}
                      dataSource={detail.data.reversals}
                      columns={[
                        { title: '状态', dataIndex: 'status' },
                        { title: '建议金额', render: (_, row) => formatMinor(row.suggestedAmountMinor, row.currency) },
                        { title: 'Run ID', dataIndex: 'runId', render: (value: string) => <span className="mono">{value}</span> },
                        { title: '生成时间', dataIndex: 'createdAt', render: formatDateTime },
                      ]}
                      scroll={{ x: 680 }}
                    />
                  ),
                },
                {
                  key: 'alerts',
                  label: `告警投递（${detail.data.alerts.length}）`,
                  children: detail.data.alerts.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无告警记录" /> : (
                    <Table
                      rowKey="id"
                      size="small"
                      pagination={false}
                      dataSource={detail.data.alerts}
                      columns={[
                        { title: '状态', dataIndex: 'status' },
                        { title: '尝试次数', dataIndex: 'attempt' },
                        { title: '创建时间', dataIndex: 'createdAt', render: formatDateTime },
                        { title: '发送时间', dataIndex: 'sentAt', render: formatDateTime },
                      ]}
                      scroll={{ x: 560 }}
                    />
                  ),
                },
              ]}
            />
          </Space>
        )}
      </Drawer>

      <Modal
        title={action === 'resolve' ? '核销差异' : '关闭差异'}
        open={Boolean(action)}
        onCancel={() => setAction(null)}
        onOk={() => form.submit()}
        okText={action === 'resolve' ? '确认核销' : '确认关闭'}
        confirmLoading={mutation.isPending}
        destroyOnHidden
      >
        <Form<ActionValues> form={form} layout="vertical" onFinish={(values) => mutation.mutate(values)} requiredMark="optional">
          <Form.Item name="operator" label="操作人" rules={[{ required: true, whitespace: true, message: '请输入操作人' }, { max: 64 }] }>
            <Input autoFocus placeholder="鉴权接入前需手工填写" />
          </Form.Item>
          <Form.Item name="note" label="处理备注" rules={[{ max: 512, message: '备注不能超过 512 字符' }] }>
            <Input.TextArea rows={4} showCount maxLength={512} placeholder="记录核验依据或关闭原因" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
