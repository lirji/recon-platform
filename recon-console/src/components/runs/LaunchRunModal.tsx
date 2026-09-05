import { useEffect, useMemo } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { App, Form, Input, InputNumber, Modal, Select } from 'antd'
import { launchRun, listScenarios } from '../../api/recon'
import { BUILTIN_SCENARIO_CODE, scenarioLabel } from '../../constants/scenario'
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
  const scenarios = useQuery({
    queryKey: ['scenarios'],
    queryFn: listScenarios,
    enabled: open,
  })

  const options = useMemo(() => {
    const enabled = (scenarios.data || []).filter((item) => item.enabled)
    const next = enabled.map((item) => ({ value: item.code, label: scenarioLabel(item.code) }))
    // 内置场景走硬编码 job:管理台停用也不挡住发起,始终出现在选项里并标注。
    if (!next.some((item) => item.value === BUILTIN_SCENARIO_CODE)) {
      next.unshift({ value: BUILTIN_SCENARIO_CODE, label: `${BUILTIN_SCENARIO_CODE}（内置，停用仍可发起）` })
    }
    return next
  }, [scenarios.data])

  useEffect(() => {
    if (!open) return
    const current = form.getFieldValue('scenarioCode') as string | undefined
    if (!current || !options.some((item) => item.value === current)) {
      form.setFieldValue('scenarioCode', options[0]?.value || BUILTIN_SCENARIO_CODE)
    }
  }, [open, options, form])

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
          scenarioCode: BUILTIN_SCENARIO_CODE,
          accountingPeriod: todayInLocalTimezone(),
          bucketCount: 64,
        }}
        onFinish={(values) => mutation.mutate(values)}
        requiredMark="optional"
      >
        <Form.Item name="scenarioCode" label="对账场景" rules={[{ required: true }]}>
          <Select
            showSearch
            optionFilterProp="label"
            loading={scenarios.isPending}
            options={options}
            placeholder="选择已启用场景"
          />
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
