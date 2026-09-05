import { Button, Card, Col, Form, Input, InputNumber, Row, Select, Space, Typography } from 'antd'
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons'
import type { DiscrepancyKind, EvaluatorType, SourceRole } from '../../api/types'
import { discrepancyTypeLabels } from '../common/StatusTag'
import { emptySegment, type ScenarioFormValue, type SegmentForm } from './scenarioJson'

const ROLES: { value: SourceRole; label: string }[] = [
  { value: 'MARKETING', label: '营销' },
  { value: 'ACCOUNTING', label: '账务' },
  { value: 'CHANNEL', label: '渠道' },
]

const EVALUATORS: { value: EvaluatorType; label: string }[] = [
  { value: 'EXACT', label: '精确' },
  { value: 'TOLERANCE', label: '容差' },
  { value: 'DROOLS', label: 'Drools（未实现会 fail-fast）' },
]

const SOURCE_TYPES = [
  { value: 'db', label: 'db' },
  { value: 'csv-file', label: 'csv-file' },
]

const TYPE_OPTIONS = Object.entries(discrepancyTypeLabels).map(([value, label]) => ({ value, label }))

interface Props {
  value: ScenarioFormValue
  disabled?: boolean
  lockCode?: boolean
  onChange: (next: ScenarioFormValue) => void
}

function updateSegment(segments: SegmentForm[], index: number, patch: Partial<SegmentForm>): SegmentForm[] {
  return segments.map((segment, i) => (i === index ? { ...segment, ...patch } : segment))
}

function ParamsEditor({
  params,
  disabled,
  onChange,
}: {
  params: { key: string; value: string }[]
  disabled?: boolean
  onChange: (next: { key: string; value: string }[]) => void
}) {
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {params.map((row, index) => (
        <Space key={`${index}-${row.key}`} align="start" style={{ width: '100%' }}>
          <Input
            aria-label="参数名"
            placeholder="参数名"
            value={row.key}
            disabled={disabled}
            onChange={(e) => onChange(params.map((item, i) => (i === index ? { ...item, key: e.target.value } : item)))}
          />
          <Input
            aria-label="参数值"
            placeholder="参数值"
            value={row.value}
            disabled={disabled}
            onChange={(e) => onChange(params.map((item, i) => (i === index ? { ...item, value: e.target.value } : item)))}
          />
          {!disabled && (
            <Button
              type="text"
              aria-label="删除参数"
              icon={<MinusCircleOutlined />}
              onClick={() => onChange(params.filter((_, i) => i !== index))}
            />
          )}
        </Space>
      ))}
      {!disabled && (
        <Button type="dashed" icon={<PlusOutlined />} onClick={() => onChange([...params, { key: '', value: '' }])}>
          添加参数
        </Button>
      )}
    </Space>
  )
}

export function ScenarioDefinitionForm({ value, disabled, lockCode, onChange }: Props) {
  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Form.Item label="场景码" required>
        <Input
          aria-label="场景码"
          className="mono"
          value={value.code}
          disabled={disabled || lockCode}
          onChange={(e) => onChange({ ...value, code: e.target.value })}
        />
      </Form.Item>

      {value.segments.map((segment, index) => (
        <Card
          key={`${segment.id}-${index}`}
          size="small"
          title={`第 ${index + 1} 段`}
          extra={
            !disabled && value.segments.length > 1 ? (
              <Button type="link" danger onClick={() => onChange({ ...value, segments: value.segments.filter((_, i) => i !== index) })}>
                删除段
              </Button>
            ) : null
          }
        >
          <Row gutter={[12, 12]}>
            <Col xs={24} md={8}>
              <Typography.Text type="secondary">段 ID</Typography.Text>
              <Input
                aria-label="段 ID"
                value={segment.id}
                disabled={disabled}
                onChange={(e) => onChange({ ...value, segments: updateSegment(value.segments, index, { id: e.target.value }) })}
              />
            </Col>
            <Col xs={24} md={8}>
              <Typography.Text type="secondary">阶段标签</Typography.Text>
              <Input
                value={segment.stageLabel}
                disabled={disabled}
                onChange={(e) => onChange({ ...value, segments: updateSegment(value.segments, index, { stageLabel: e.target.value }) })}
              />
            </Col>
            <Col xs={24} md={8}>
              <Typography.Text type="secondary">桥接角色</Typography.Text>
              <Select
                allowClear
                style={{ width: '100%' }}
                options={ROLES}
                value={segment.spineRole ?? undefined}
                disabled={disabled}
                onChange={(spineRole) => onChange({ ...value, segments: updateSegment(value.segments, index, { spineRole: spineRole ?? null }) })}
              />
            </Col>
            <Col xs={12} md={8}>
              <Typography.Text type="secondary">左侧角色</Typography.Text>
              <Select
                style={{ width: '100%' }}
                options={ROLES}
                value={segment.leftRole}
                disabled={disabled}
                onChange={(leftRole) => onChange({ ...value, segments: updateSegment(value.segments, index, { leftRole }) })}
              />
            </Col>
            <Col xs={12} md={8}>
              <Typography.Text type="secondary">右侧角色</Typography.Text>
              <Select
                style={{ width: '100%' }}
                options={ROLES}
                value={segment.rightRole}
                disabled={disabled}
                onChange={(rightRole) => onChange({ ...value, segments: updateSegment(value.segments, index, { rightRole }) })}
              />
            </Col>
            <Col xs={12} md={8}>
              <Typography.Text type="secondary">匹配键字段</Typography.Text>
              <Input
                className="mono"
                value={segment.matchKeyField}
                disabled={disabled}
                onChange={(e) => onChange({ ...value, segments: updateSegment(value.segments, index, { matchKeyField: e.target.value }) })}
              />
            </Col>
            <Col xs={12} md={8}>
              <Typography.Text type="secondary">分组键字段</Typography.Text>
              <Input
                className="mono"
                value={segment.groupKeyField}
                disabled={disabled}
                onChange={(e) => onChange({ ...value, segments: updateSegment(value.segments, index, { groupKeyField: e.target.value }) })}
              />
            </Col>
            <Col xs={12} md={8}>
              <Typography.Text type="secondary">判差器</Typography.Text>
              <Select
                style={{ width: '100%' }}
                options={EVALUATORS}
                value={segment.rule.evaluatorType}
                disabled={disabled}
                onChange={(evaluatorType) =>
                  onChange({
                    ...value,
                    segments: updateSegment(value.segments, index, { rule: { ...segment.rule, evaluatorType } }),
                  })
                }
              />
            </Col>
            <Col xs={12} md={8}>
              <Typography.Text type="secondary">绝对容差（分）</Typography.Text>
              <Input
                aria-label="绝对容差"
                className="mono"
                value={segment.rule.absToleranceMinor}
                disabled={disabled}
                onChange={(e) =>
                  onChange({
                    ...value,
                    segments: updateSegment(value.segments, index, {
                      rule: { ...segment.rule, absToleranceMinor: e.target.value },
                    }),
                  })
                }
              />
            </Col>
            <Col xs={12} md={8}>
              <Typography.Text type="secondary">比例容差（bps）</Typography.Text>
              <InputNumber
                style={{ width: '100%' }}
                min={0}
                value={segment.rule.ratioToleranceBps}
                disabled={disabled}
                onChange={(ratioToleranceBps) =>
                  onChange({
                    ...value,
                    segments: updateSegment(value.segments, index, {
                      rule: { ...segment.rule, ratioToleranceBps: ratioToleranceBps ?? 0 },
                    }),
                  })
                }
              />
            </Col>
            <Col span={24}>
              <Typography.Text type="secondary">启用差异类型（空=全部）</Typography.Text>
              <Select
                mode="multiple"
                allowClear
                style={{ width: '100%' }}
                options={TYPE_OPTIONS}
                value={segment.rule.enabledTypes ?? []}
                disabled={disabled}
                onChange={(enabledTypes: DiscrepancyKind[]) =>
                  onChange({
                    ...value,
                    segments: updateSegment(value.segments, index, {
                      rule: { ...segment.rule, enabledTypes: enabledTypes.length ? enabledTypes : null },
                    }),
                  })
                }
              />
            </Col>
            {(['left', 'right'] as const).map((side) => (
              <Col xs={24} md={12} key={side}>
                <Typography.Text type="secondary">{side === 'left' ? '左侧数据源' : '右侧数据源'}</Typography.Text>
                <Select
                  style={{ width: '100%', margin: '4px 0 8px' }}
                  options={SOURCE_TYPES}
                  value={segment[side].sourceType}
                  disabled={disabled}
                  onChange={(sourceType) =>
                    onChange({
                      ...value,
                      segments: updateSegment(value.segments, index, {
                        [side]: { ...segment[side], sourceType },
                      }),
                    })
                  }
                />
                <ParamsEditor
                  params={segment[side].params}
                  disabled={disabled}
                  onChange={(params) =>
                    onChange({
                      ...value,
                      segments: updateSegment(value.segments, index, {
                        [side]: { ...segment[side], params },
                      }),
                    })
                  }
                />
              </Col>
            ))}
          </Row>
        </Card>
      ))}

      {!disabled && (
        <Button
          icon={<PlusOutlined />}
          onClick={() => onChange({ ...value, segments: [...value.segments, emptySegment(`SEG${value.segments.length + 1}`)] })}
        >
          添加一段
        </Button>
      )}
    </Space>
  )
}
