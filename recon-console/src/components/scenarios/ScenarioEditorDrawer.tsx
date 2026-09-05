import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { App, Alert, Button, Collapse, Drawer, Grid, Input, Modal, Space, Switch, Typography } from 'antd'
import { getScenario, saveScenario } from '../../api/recon'
import { useAuth } from '../../auth/AuthContext'
import { isBuiltinScenario } from '../../constants/scenario'
import { ErrorState, PageSkeleton } from '../common/AsyncState'
import { errorMessage } from '../../utils/format'
import { ScenarioDefinitionForm } from './ScenarioDefinitionForm'
import { parseScenarioForm, serializeScenarioForm, type ScenarioFormValue } from './scenarioJson'

export interface EditingScenario {
  mode: 'new' | 'edit'
  code: string | null
}

interface Props {
  editing: EditingScenario | null
  existingCodes: string[]
  onClose: () => void
}

// 新建模板:2 段骨架(与营销三方同形态,可经通用引擎跑通);用户改 code 与列映射即可。
const TEMPLATE = JSON.stringify(
  {
    code: 'NEW_SCENARIO',
    segments: [
      {
        id: 'SEG1',
        leftRole: 'MARKETING',
        rightRole: 'ACCOUNTING',
        spineRole: 'ACCOUNTING',
        stageLabel: 'SEG1',
        matchKeyField: 'issueId',
        groupKeyField: 'orderNo',
        left: { sourceType: 'db', params: { table: 't_left', matchKeyColumn: 'issue_id' } },
        right: { sourceType: 'db', params: { table: 't_spine', matchKeyColumn: 'issue_id' } },
        rule: { evaluatorType: 'EXACT', absToleranceMinor: 0, ratioToleranceBps: 0, enabledTypes: null },
      },
      {
        id: 'SEG2',
        leftRole: 'ACCOUNTING',
        rightRole: 'CHANNEL',
        spineRole: 'ACCOUNTING',
        stageLabel: 'SEG2',
        matchKeyField: 'channelSerialNo',
        groupKeyField: 'channelSerialNo',
        left: { sourceType: 'db', params: { table: 't_spine', matchKeyColumn: 'channel_serial_no' } },
        right: { sourceType: 'db', params: { table: 't_right', matchKeyColumn: 'channel_serial_no' } },
        rule: { evaluatorType: 'EXACT', absToleranceMinor: 0, ratioToleranceBps: 0, enabledTypes: null },
      },
    ],
  },
  null,
  2,
)

export function ScenarioEditorDrawer({ editing, existingCodes, onClose }: Props) {
  const screens = Grid.useBreakpoint()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const canWrite = useAuth().can('recon.launch')

  const detail = useQuery({
    queryKey: ['scenario-detail', editing?.code],
    queryFn: () => getScenario(editing!.code!),
    enabled: editing?.mode === 'edit' && Boolean(editing?.code),
  })

  const [jsonText, setJsonText] = useState('')
  const [formValue, setFormValue] = useState<ScenarioFormValue | null>(null)
  const [enabled, setEnabled] = useState(true)
  const [parseError, setParseError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const jsonDirtyRef = useRef(false)

  // 播种:仅当 (mode:code) 变化才 seed,ref-guard 防后台 refetch 覆盖用户编辑、防切换场景残留。
  const seededKeyRef = useRef<string | null>(null)
  const seedKey = editing ? `${editing.mode}:${editing.code ?? 'new'}` : null
  useEffect(() => {
    if (!editing) {
      seededKeyRef.current = null
      return
    }
    if (editing.mode === 'new') {
      if (seededKeyRef.current !== seedKey) {
        setJsonText(TEMPLATE)
        setFormValue(parseScenarioForm(TEMPLATE))
        setEnabled(true)
        setParseError(null)
        setSaveError(null)
        jsonDirtyRef.current = false
        seededKeyRef.current = seedKey
      }
    } else if (detail.data && seededKeyRef.current !== seedKey) {
      const text = JSON.stringify(detail.data.definition, null, 2)
      setJsonText(text)
      setFormValue(parseScenarioForm(text))
      setEnabled(detail.data.enabled) // M1: enabled 从 detail 播种,避免不碰开关静默翻转
      setParseError(null)
      setSaveError(null)
      jsonDirtyRef.current = false
      seededKeyRef.current = seedKey
    }
  }, [editing, seedKey, detail.data])

  const mutation = useMutation({
    mutationFn: (code: string) => saveScenario(code, jsonText, enabled),
    onSuccess: async (view) => {
      message.success(`场景已保存：${view.code}`)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['scenarios'] }),
        queryClient.invalidateQueries({ queryKey: ['scenario-detail', view.code] }),
      ])
      onClose()
    },
    onError: (error) => {
      const msg = errorMessage(error)
      setSaveError(msg)
      message.error(msg)
    },
  })

  const handleSave = () => {
    setSaveError(null)
    setParseError(null)
    let parsed: { code?: string }
    try {
      // 仅用于语法校验 + 读 code;提交发 jsonText 原文(见 saveScenario)。此校验亦防非法 JSON 被 axios 双重编码。
      parsed = JSON.parse(jsonText)
    } catch (e) {
      setParseError(`JSON 语法错误：${e instanceof Error ? e.message : String(e)}`)
      return
    }
    const pathCode = editing?.mode === 'edit' ? editing.code! : parsed.code
    if (!pathCode) {
      setParseError('定义缺少 code')
      return
    }
    if (parsed.code !== pathCode) {
      setParseError('JSON 中的 code 与目标场景不一致（编辑模式不可改 code）')
      return
    }
    if (editing?.mode === 'new' && existingCodes.includes(pathCode)) {
      Modal.confirm({
        title: `场景 ${pathCode} 已存在`,
        content: '保存将覆盖现有定义,确认继续?',
        okText: '覆盖保存',
        cancelText: '取消',
        onOk: () => mutation.mutate(pathCode),
      })
      return
    }
    mutation.mutate(pathCode)
  }

  const applyForm = (next: ScenarioFormValue) => {
    setFormValue(next)
    jsonDirtyRef.current = false
    setParseError(null)
    try {
      setJsonText(serializeScenarioForm(next))
    } catch (e) {
      setParseError(e instanceof Error ? e.message : String(e))
    }
  }

  const applyJson = (text: string) => {
    setJsonText(text)
    jsonDirtyRef.current = true
    const parsed = parseScenarioForm(text)
    if (parsed) {
      setFormValue(parsed)
      setParseError(null)
    }
  }

  const title = editing?.mode === 'new' ? '新建场景' : `编辑场景 ${editing?.code ?? ''}`

  return (
    <Drawer
      title={title}
      open={Boolean(editing)}
      onClose={onClose}
      destroyOnHidden
      width={screens.md ? 900 : '100%'}
    >
      {editing?.mode === 'edit' && detail.isPending && <PageSkeleton />}
      {editing?.mode === 'edit' && detail.isError && (
        <ErrorState message={errorMessage(detail.error)} onRetry={() => void detail.refetch()} />
      )}
      {(editing?.mode === 'new' || detail.data) && (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {saveError && <Alert type="error" showIcon message="保存失败" description={saveError} />}
          {isBuiltinScenario(editing?.code) && (
            <Alert
              type="warning"
              showIcon
              message="这是内置场景 MARKETING_3WAY"
              description="停用只影响配置目录的理解，不会挡住运行管理里的硬编码发起路径。"
            />
          )}
          {/* 操作行置顶:规避移动端软键盘遮挡底部按钮 */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <Space>
              <Typography.Text>启用</Typography.Text>
              <Switch
                checked={enabled}
                onChange={setEnabled}
                disabled={!canWrite}
                checkedChildren="启用"
                unCheckedChildren="停用"
              />
              {editing?.mode === 'edit' && detail.data && (
                <Typography.Text type="secondary">版本 v{detail.data.version}</Typography.Text>
              )}
            </Space>
            {canWrite && (
              <Button type="primary" onClick={handleSave} loading={mutation.isPending}>
                保存
              </Button>
            )}
          </div>
          {formValue && (
            <ScenarioDefinitionForm
              value={formValue}
              disabled={!canWrite}
              lockCode={editing?.mode === 'edit'}
              onChange={applyForm}
            />
          )}
          <Collapse
            defaultActiveKey={['json']}
            items={[
              {
                key: 'json',
                label: '场景定义 JSON',
                children: (
                  <div>
                    <Typography.Text type="secondary">
                      保存提交原文。绝对容差走字符串字段，避免大于 2^53 的分值被 Number 舍入。
                    </Typography.Text>
                    <Input.TextArea
                      className="mono"
                      aria-label="场景定义 JSON"
                      value={jsonText}
                      onChange={(e) => applyJson(e.target.value)}
                      autoSize={{ minRows: 12, maxRows: 26 }}
                      readOnly={!canWrite}
                      status={parseError ? 'error' : undefined}
                      style={{ marginTop: 8 }}
                    />
                    {parseError && <Typography.Text type="danger">{parseError}</Typography.Text>}
                  </div>
                ),
              },
            ]}
          />
        </Space>
      )}
    </Drawer>
  )
}
