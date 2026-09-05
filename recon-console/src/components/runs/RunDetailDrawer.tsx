import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { App, Button, Descriptions, Drawer, Empty, Grid, Popconfirm, Space, Tabs } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { getRun, listScenarios, rerunRun } from '../../api/recon'
import { useAuth } from '../../auth/AuthContext'
import { ErrorState, PageSkeleton } from '../common/AsyncState'
import { RunStatusTag } from '../common/StatusTag'
import { errorMessage, formatDateTime } from '../../utils/format'
import { ConservationReportTable } from './ConservationReportTable'
import { RefineViolationsAlert } from './RefineViolationsAlert'
import { RejectsPanel } from './RejectsPanel'
import { ThreeWayRollupPanel } from './ThreeWayRollupPanel'

interface Props {
  runId: string | null
  onClose: () => void
}

export function RunDetailDrawer({ runId, onClose }: Props) {
  const screens = Grid.useBreakpoint()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const canLaunch = useAuth().can('recon.launch')
  const [activeKey, setActiveKey] = useState('conservation')
  const detail = useQuery({
    queryKey: ['run-detail', runId],
    queryFn: () => getRun(runId!),
    enabled: Boolean(runId),
  })
  const scenarios = useQuery({
    queryKey: ['scenarios'],
    queryFn: listScenarios,
    enabled: Boolean(runId),
  })
  const rerun = useMutation({
    mutationFn: () => rerunRun(runId!),
    onSuccess: async (result) => {
      message.success(`重跑完成：${result.runId}`)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
        queryClient.invalidateQueries({ queryKey: ['runs'] }),
        queryClient.invalidateQueries({ queryKey: ['run-detail', runId] }),
        queryClient.invalidateQueries({ queryKey: ['discrepancies'] }),
        queryClient.invalidateQueries({ queryKey: ['three-way', runId] }),
        queryClient.invalidateQueries({ queryKey: ['refine-violations', runId] }),
        queryClient.invalidateQueries({ queryKey: ['run-rejects', runId] }),
      ])
    },
    onError: (error) => message.error(errorMessage(error)),
  })

  const run = detail.data?.run
  const reports = detail.data?.reports || []
  const distinctSegments = new Set(reports.map((row) => row.segmentId)).size
  const catalogSegments = scenarios.data?.find((item) => item.code === run?.scenarioCode)?.segmentCount ?? 0
  const showThreeWay = distinctSegments >= 2 || catalogSegments >= 2

  const conservationReport =
    reports.length === 0 ? (
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该 Run 尚未生成报表" />
    ) : (
      <ConservationReportTable reports={reports} />
    )

  return (
    <Drawer
      title="运行详情"
      open={Boolean(runId)}
      onClose={onClose}
      width={screens.md ? 960 : '100%'}
      extra={
        runId && canLaunch && (
          <Popconfirm title="确认重跑当前 Run？" description="机器结果会重算，人工处置和审计会保留。" onConfirm={() => rerun.mutate()}>
            <Button icon={<ReloadOutlined />} loading={rerun.isPending}>重跑</Button>
          </Popconfirm>
        )
      }
    >
      {detail.isPending && <PageSkeleton />}
      {detail.isError && <ErrorState message={errorMessage(detail.error)} onRetry={() => void detail.refetch()} />}
      {detail.data && run && (
        <Space direction="vertical" size={24} style={{ width: '100%' }}>
          <RefineViolationsAlert runId={run.runId} />

          <Descriptions title="运行信息" bordered size="small" column={screens.md ? 2 : 1}>
            <Descriptions.Item label="Run ID"><span className="mono">{run.runId}</span></Descriptions.Item>
            <Descriptions.Item label="状态"><RunStatusTag status={run.status} /></Descriptions.Item>
            <Descriptions.Item label="场景">{run.scenarioCode}</Descriptions.Item>
            <Descriptions.Item label="账期">{run.accountingPeriod}</Descriptions.Item>
            <Descriptions.Item label="序号">#{run.sequenceNo}</Descriptions.Item>
            <Descriptions.Item label="分桶数">{run.bucketCount}</Descriptions.Item>
            <Descriptions.Item label="差异数">{run.discrepancyCount}</Descriptions.Item>
            <Descriptions.Item label="待处理">{run.openDiscrepancyCount}</Descriptions.Item>
            <Descriptions.Item label="开始时间">{formatDateTime(run.startedAt)}</Descriptions.Item>
            <Descriptions.Item label="结束时间">{formatDateTime(run.finishedAt)}</Descriptions.Item>
          </Descriptions>

          <Tabs
            activeKey={activeKey}
            onChange={setActiveKey}
            items={[
              { key: 'conservation', label: '守恒报表', children: conservationReport },
              ...(showThreeWay
                ? [{
                    key: 'three-way',
                    label: '三方合并',
                    children: <ThreeWayRollupPanel runId={run.runId} enabled={activeKey === 'three-way'} />,
                  }]
                : []),
              {
                key: 'rejects',
                label: '拒绝行',
                children: <RejectsPanel runId={run.runId} enabled={activeKey === 'rejects'} />,
              },
            ]}
          />
        </Space>
      )}
    </Drawer>
  )
}
