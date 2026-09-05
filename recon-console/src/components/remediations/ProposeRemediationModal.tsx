import { useEffect } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { App, Alert, Form, Input, Modal, Select } from 'antd'
import { proposeRemediation } from '../../api/recon'
import type { ProposeRemediationRequest, RemediationAction } from '../../api/types'
import { AUTH_CONFIG } from '../../auth/config'
import { errorMessage } from '../../utils/format'

export interface ProposePrefill {
  tenantId?: string
  scenarioCode?: string
  discrepancyRef?: string
  awardItemNo?: string
}

interface Props {
  open: boolean
  prefill?: ProposePrefill | null
  onClose: () => void
}

interface Values {
  tenantId: string
  scenarioCode: string
  discrepancyRef: string
  awardItemNo: string
  originalOperationNo?: string
  action: RemediationAction
  reason: string
}

const ACTIONS = [
  { value: 'REISSUE', label: '补发' },
  { value: 'REVERSE', label: '冲正' },
  { value: 'MANUAL_REVIEW', label: '人工复核' },
]

export function ProposeRemediationModal({ open, prefill, onClose }: Props) {
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [form] = Form.useForm<Values>()
  const action = Form.useWatch('action', form)

  useEffect(() => {
    if (!open) return
    form.setFieldsValue({
      tenantId: prefill?.tenantId || AUTH_CONFIG.organization,
      scenarioCode: prefill?.scenarioCode || '',
      discrepancyRef: prefill?.discrepancyRef || '',
      awardItemNo: prefill?.awardItemNo || '',
      originalOperationNo: '',
      action: 'MANUAL_REVIEW',
      reason: '',
    })
  }, [open, prefill, form])

  const mutation = useMutation({
    mutationFn: (values: Values) => {
      const request: ProposeRemediationRequest = {
        tenantId: values.tenantId.trim(),
        scenarioCode: values.scenarioCode.trim(),
        discrepancyRef: values.discrepancyRef.trim(),
        awardItemNo: values.awardItemNo.trim(),
        action: values.action,
        reason: values.reason.trim(),
        originalOperationNo: values.originalOperationNo?.trim() || undefined,
      }
      return proposeRemediation(request)
    },
    onSuccess: async () => {
      message.success('已提出补救建议')
      await queryClient.invalidateQueries({ queryKey: ['benefit-remediations'] })
      onClose()
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  return (
    <Modal
      title="提出权益补救"
      open={open}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText="提交建议"
      confirmLoading={mutation.isPending}
      destroyOnHidden
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="只生成建议，不会动资金。批准后写入 command outbox；relay 默认关闭，不会自动闭环差异。"
      />
      <Form<Values> form={form} layout="vertical" onFinish={(values) => mutation.mutate(values)}>
        <Form.Item name="tenantId" label="租户" rules={[{ required: true, message: '请填写租户' }]}>
          <Input aria-label="租户" className="mono" />
        </Form.Item>
        <Form.Item name="scenarioCode" label="场景" rules={[{ required: true, message: '请填写场景码' }]}>
          <Input aria-label="场景" className="mono" />
        </Form.Item>
        <Form.Item name="discrepancyRef" label="差异引用" rules={[{ required: true, message: '请填写差异引用' }]}>
          <Input aria-label="差异引用" className="mono" />
        </Form.Item>
        <Form.Item name="awardItemNo" label="权益发放项" rules={[{ required: true, message: '请填写发放项号' }]}>
          <Input aria-label="权益发放项" className="mono" />
        </Form.Item>
        <Form.Item name="action" label="动作" rules={[{ required: true }]}>
          <Select options={ACTIONS} />
        </Form.Item>
        {action !== 'MANUAL_REVIEW' && (
          <Form.Item
            name="originalOperationNo"
            label="原操作号"
            rules={[{ required: true, message: '可自动执行的补救必须填写原操作号' }]}
          >
            <Input className="mono" placeholder="automatable remediation requires originalOperationNo" />
          </Form.Item>
        )}
        <Form.Item name="reason" label="原因" rules={[{ required: true, message: '请填写原因' }, { max: 512 }]}>
          <Input.TextArea aria-label="原因" rows={3} maxLength={512} showCount />
        </Form.Item>
      </Form>
    </Modal>
  )
}
