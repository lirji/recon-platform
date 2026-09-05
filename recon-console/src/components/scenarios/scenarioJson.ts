import type { DiscrepancyKind, EvaluatorType, SourceRole } from '../../api/types'

export interface SourceForm {
  sourceType: string
  params: { key: string; value: string }[]
}

export interface SegmentForm {
  id: string
  leftRole: SourceRole
  rightRole: SourceRole
  spineRole: SourceRole | null
  stageLabel: string
  matchKeyField: string
  groupKeyField: string
  left: SourceForm
  right: SourceForm
  rule: {
    evaluatorType: EvaluatorType
    absToleranceMinor: string
    ratioToleranceBps: number
    enabledTypes: DiscrepancyKind[] | null
  }
}

export interface ScenarioFormValue {
  code: string
  segments: SegmentForm[]
}

const ROLES: SourceRole[] = ['MARKETING', 'ACCOUNTING', 'CHANNEL']

function asRole(value: unknown, fallback: SourceRole): SourceRole {
  return typeof value === 'string' && ROLES.includes(value as SourceRole) ? (value as SourceRole) : fallback
}

function asParams(value: unknown): SourceForm['params'] {
  if (!value || typeof value !== 'object') return []
  return Object.entries(value as Record<string, unknown>).map(([key, item]) => ({
    key,
    value: item == null ? '' : String(item),
  }))
}

function paramsObject(params: SourceForm['params']): Record<string, string> {
  const out: Record<string, string> = {}
  for (const row of params) {
    if (row.key.trim()) out[row.key.trim()] = row.value
  }
  return out
}

/** 从原文抽出每个 absToleranceMinor 的十进制字面量,避免 JSON.parse 把 >2^53 的 long 舍入。 */
function extractAbsLiterals(jsonText: string): string[] {
  return [...jsonText.matchAll(/"absToleranceMinor"\s*:\s*(-?\d+)/g)].map((match) => match[1])
}

export function emptySegment(id: string): SegmentForm {
  return {
    id,
    leftRole: 'MARKETING',
    rightRole: 'ACCOUNTING',
    spineRole: 'ACCOUNTING',
    stageLabel: id,
    matchKeyField: 'issueId',
    groupKeyField: 'orderNo',
    left: { sourceType: 'db', params: [{ key: 'table', value: 't_left' }, { key: 'matchKeyColumn', value: 'issue_id' }] },
    right: { sourceType: 'db', params: [{ key: 'table', value: 't_spine' }, { key: 'matchKeyColumn', value: 'issue_id' }] },
    rule: { evaluatorType: 'EXACT', absToleranceMinor: '0', ratioToleranceBps: 0, enabledTypes: null },
  }
}

export function parseScenarioForm(jsonText: string): ScenarioFormValue | null {
  try {
    const parsed = JSON.parse(jsonText) as {
      code?: unknown
      segments?: Array<Record<string, unknown>>
    }
    if (!parsed || typeof parsed !== 'object') return null
    const absLiterals = extractAbsLiterals(jsonText)
    const segments = Array.isArray(parsed.segments) ? parsed.segments : []
    return {
      code: typeof parsed.code === 'string' ? parsed.code : '',
      segments: segments.map((segment, index) => {
        const rule = (segment.rule && typeof segment.rule === 'object' ? segment.rule : {}) as Record<string, unknown>
        const left = (segment.left && typeof segment.left === 'object' ? segment.left : {}) as Record<string, unknown>
        const right = (segment.right && typeof segment.right === 'object' ? segment.right : {}) as Record<string, unknown>
        const absFromLiteral = absLiterals[index]
        const absFromParsed = rule.absToleranceMinor
        return {
          id: typeof segment.id === 'string' ? segment.id : `SEG${index + 1}`,
          leftRole: asRole(segment.leftRole, 'MARKETING'),
          rightRole: asRole(segment.rightRole, 'ACCOUNTING'),
          spineRole: segment.spineRole == null || segment.spineRole === '' ? null : asRole(segment.spineRole, 'ACCOUNTING'),
          stageLabel: typeof segment.stageLabel === 'string' ? segment.stageLabel : '',
          matchKeyField: typeof segment.matchKeyField === 'string' ? segment.matchKeyField : '',
          groupKeyField: typeof segment.groupKeyField === 'string' ? segment.groupKeyField : '',
          left: {
            sourceType: typeof left.sourceType === 'string' ? left.sourceType : 'db',
            params: asParams(left.params),
          },
          right: {
            sourceType: typeof right.sourceType === 'string' ? right.sourceType : 'db',
            params: asParams(right.params),
          },
          rule: {
            evaluatorType: (typeof rule.evaluatorType === 'string' ? rule.evaluatorType : 'EXACT') as EvaluatorType,
            absToleranceMinor: absFromLiteral ?? (absFromParsed == null ? '0' : String(absFromParsed)),
            ratioToleranceBps: typeof rule.ratioToleranceBps === 'number' ? rule.ratioToleranceBps : Number(rule.ratioToleranceBps) || 0,
            enabledTypes: Array.isArray(rule.enabledTypes) ? (rule.enabledTypes as DiscrepancyKind[]) : null,
          },
        }
      }),
    }
  } catch {
    return null
  }
}

/** 序列化时 absToleranceMinor 以原文数字写入,绝不经 JS Number。 */
export function serializeScenarioForm(value: ScenarioFormValue): string {
  const segments = value.segments.map((segment) => {
    const abs = segment.rule.absToleranceMinor.trim() === '' ? '0' : segment.rule.absToleranceMinor.trim()
    if (!/^-?\d+$/.test(abs)) {
      throw new Error(`absToleranceMinor 必须是整数：${abs}`)
    }
    return {
      id: segment.id,
      leftRole: segment.leftRole,
      rightRole: segment.rightRole,
      spineRole: segment.spineRole,
      stageLabel: segment.stageLabel,
      matchKeyField: segment.matchKeyField,
      groupKeyField: segment.groupKeyField,
      left: { sourceType: segment.left.sourceType, params: paramsObject(segment.left.params) },
      right: { sourceType: segment.right.sourceType, params: paramsObject(segment.right.params) },
      rule: {
        evaluatorType: segment.rule.evaluatorType,
        absToleranceMinor: `__ABS__${abs}__`,
        ratioToleranceBps: Number.isFinite(segment.rule.ratioToleranceBps) ? segment.rule.ratioToleranceBps : 0,
        enabledTypes: segment.rule.enabledTypes && segment.rule.enabledTypes.length > 0 ? segment.rule.enabledTypes : null,
      },
    }
  })
  const text = JSON.stringify({ code: value.code, segments }, null, 2)
  return text.replace(/"__ABS__(-?\d+)__"/g, '$1')
}
