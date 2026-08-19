import { useQuery } from '@tanstack/react-query'
import { CheckCircleOutlined, CloseCircleOutlined, MinusCircleOutlined } from '@ant-design/icons'
import { Alert, Empty, Space, Typography } from 'antd'
import { getThreeWayReport } from '../../api/recon'
import { errorMessage } from '../../utils/format'
import { ErrorState, PageSkeleton } from '../common/AsyncState'
import { CurrencyRollupCard } from './CurrencyRollupCard'

interface Props {
  runId: string
  enabled: boolean
}

// 整体三方一致性 banner:threeWayBalanced 三态(true 全平 / false 不一致 / null 无报表),颜色非唯一手段(配文案+图标)。
function ThreeWayBalanceBanner({ balanced, inconsistentCount }: { balanced: boolean | null; inconsistentCount: number }) {
  if (balanced === true) {
    return <Alert type="success" showIcon icon={<CheckCircleOutlined />} message="三方守恒 · 全平" description="所有币种两段链路均齐备且守恒。" />
  }
  if (balanced === false) {
    return (
      <Alert
        type="error"
        showIcon
        icon={<CloseCircleOutlined />}
        message="三方不一致"
        description={`存在 ${inconsistentCount} 个币种链路不齐或守恒异常，请下钻排查。`}
      />
    )
  }
  return <Alert type="info" showIcon icon={<MinusCircleOutlined />} message="无三方报表" description="该 Run 尚未生成三方链路报表。" />
}

export function ThreeWayRollupPanel({ runId, enabled }: Props) {
  const report = useQuery({
    queryKey: ['three-way', runId],
    queryFn: () => getThreeWayReport(runId),
    enabled: enabled && Boolean(runId),
  })

  if (report.isLoading) return <PageSkeleton />
  if (report.isError) return <ErrorState message={errorMessage(report.error)} onRetry={() => void report.refetch()} />

  const data = report.data
  if (!data || data.currencies.length === 0 || data.threeWayBalanced == null) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该 Run 无三方链路报表" />
  }

  const inconsistentCount = data.currencies.filter((c) => !c.threeWayConsistent).length

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <ThreeWayBalanceBanner balanced={data.threeWayBalanced} inconsistentCount={inconsistentCount} />
      <Typography.Text type="secondary">
        共 {data.currencies.length} 个币种，其中不一致 {inconsistentCount} 个。金额并列展示、不跨段求和(避免重复计账务 spine)。
      </Typography.Text>
      {data.currencies.map((rollup) => (
        <CurrencyRollupCard key={rollup.currency} rollup={rollup} runId={runId} />
      ))}
    </Space>
  )
}
