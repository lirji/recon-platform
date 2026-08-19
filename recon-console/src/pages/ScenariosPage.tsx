import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { EditOutlined, PlusOutlined } from '@ant-design/icons'
import { Button, Card, Grid, Space, Table } from 'antd'
import { listScenarios } from '../api/recon'
import type { ScenarioSummary } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { EmptyState, ErrorState } from '../components/common/AsyncState'
import { PageHeader } from '../components/common/PageHeader'
import { ScenarioEnabledTag } from '../components/scenarios/ScenarioEnabledTag'
import { ScenarioEditorDrawer, type EditingScenario } from '../components/scenarios/ScenarioEditorDrawer'
import { errorMessage } from '../utils/format'

export function ScenariosPage() {
  const screens = Grid.useBreakpoint()
  const canWrite = useAuth().can('recon.launch')
  const [editing, setEditing] = useState<EditingScenario | null>(null)

  const scenarios = useQuery({ queryKey: ['scenarios'], queryFn: listScenarios })
  const rows = scenarios.data || []

  const openEdit = (code: string) => setEditing({ mode: 'edit', code })

  const columns = [
    {
      title: '场景码',
      dataIndex: 'code',
      width: 260,
      render: (code: string) => (
        <button className="cell-link" onClick={() => openEdit(code)}>
          <strong className="mono">{code}</strong>
        </button>
      ),
    },
    { title: '段数', dataIndex: 'segmentCount', width: 90 },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 100,
      render: (enabled: boolean) => <ScenarioEnabledTag enabled={enabled} />,
    },
    { title: '版本', dataIndex: 'version', width: 90, render: (v: number) => `v${v}` },
    {
      title: '操作',
      fixed: 'right' as const,
      width: 90,
      render: (_: unknown, row: ScenarioSummary) => (
        <Button type="link" icon={<EditOutlined />} onClick={() => openEdit(row.code)}>
          {canWrite ? '编辑' : '查看'}
        </Button>
      ),
    },
  ]

  return (
    <>
      <PageHeader
        eyebrow="SCENARIO CATALOG"
        title="场景管理"
        description="查看与维护对账场景定义(责任链各段、数据源、判差规则)。启用的场景可在运行管理中发起。"
        extra={
          canWrite ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing({ mode: 'new', code: null })}>
              新建场景
            </Button>
          ) : undefined
        }
      />

      <Card className="data-card">
        {scenarios.isError && (
          <ErrorState message={errorMessage(scenarios.error)} onRetry={() => void scenarios.refetch()} />
        )}
        {!scenarios.isError && screens.md && (
          <Table<ScenarioSummary>
            rowKey="code"
            columns={columns}
            dataSource={rows}
            loading={scenarios.isPending || scenarios.isFetching}
            pagination={false}
            scroll={{ x: 700 }}
            locale={{ emptyText: <EmptyState /> }}
          />
        )}
        {!scenarios.isError && !screens.md && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {rows.map((item) => (
              <button className="mobile-data-card" key={item.code} onClick={() => openEdit(item.code)}>
                <span className="mobile-card-heading">
                  <strong className="mono">{item.code}</strong>
                  <ScenarioEnabledTag enabled={item.enabled} />
                </span>
                <span className="mobile-card-stats">
                  <span>{item.segmentCount} 段</span>
                  <span>v{item.version}</span>
                </span>
              </button>
            ))}
            {!scenarios.isPending && rows.length === 0 && <EmptyState />}
          </Space>
        )}
      </Card>

      <ScenarioEditorDrawer
        editing={editing}
        existingCodes={rows.map((r) => r.code)}
        onClose={() => setEditing(null)}
      />
    </>
  )
}
