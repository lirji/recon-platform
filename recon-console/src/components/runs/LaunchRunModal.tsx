import { useMutation, useQueryClient } from '@tanstack/react-query'
import { App, Form, Input, InputNumber, Modal, Select } from 'antd'
import { launchRun } from '../../api/recon'
import { errorMessage } from '../../utils/format'

interface LaunchValues {
  scenarioCode: string
  accountingPeriod: string
  bucketCount: number
}

interface Props {
  open: boolean
  onClose: () => void
  onLaunched?: (runId: string) => void
}

function todayInLocalTimezone(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function LaunchRunModal({ open, onClose, onLaunched }: Props) {
  const [form] = Form.useForm<LaunchValues>()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const mutation = useMutation({
    mutationFn: launchRun,
    onSuccess: async (result) => {
      message.success(`对账任务已完成发起：${result.runId}`)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
        queryClient.invalidateQueries({ queryKey: ['runs'] }),
      ])
      form.resetFields()
      onClose()
      onLaunched?.(result.runId)
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  return (
    <Modal
      title="发起对账任务"
      open={open}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText="发起任务"
      cancelText="取消"
      confirmLoading={mutation.isPending}
      destroyOnHidden
    >
      <Form<LaunchValues>
        form={form}
        layout="vertical"
        initialValues={{
          scenarioCode: 'MARKETING_3WAY',
          accountingPeriod: todayInLocalTimezone(),
          bucketCount: 64,
        }}
        onFinish={(values) => mutation.mutate(values)}
        requiredMark="optional"
      >
        <Form.Item name="scenarioCode" label="对账场景" rules={[{ required: true }]}>
          <Select options={[{ value: 'MARKETING_3WAY', label: '营销三方对账' }]} />
        </Form.Item>
        <Form.Item
          name="accountingPeriod"
          label="账期"
          rules={[
            { required: true, message: '请选择账期' },
            { pattern: /^\d{4}-\d{2}-\d{2}$/, message: '账期格式应为 YYYY-MM-DD' },
          ]}
        >
          <Input type="date" />
        </Form.Item>
        <Form.Item
          name="bucketCount"
          label="分桶数"
          extra="范围 1–4096；默认 64，生产值应与数据库连接池容量匹配。"
          rules={[{ required: true, message: '请输入分桶数' }]}
        >
          <InputNumber min={1} max={4096} precision={0} style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Modal>
  )
}
