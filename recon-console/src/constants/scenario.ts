/** 内置营销三方场景。ReconLaunchService 对其走硬编码 job,管理台停用不挡住发起。 */
export const BUILTIN_SCENARIO_CODE = 'MARKETING_3WAY'

export const DEFAULT_SEGMENT_OPTIONS = [
  { value: 'SEG1_MKT_ACCT', label: 'SEG1 营销 ↔ 账务' },
  { value: 'SEG2_ACCT_CHANNEL', label: 'SEG2 账务 ↔ 渠道' },
]

export function isBuiltinScenario(code: string | null | undefined): boolean {
  return code === BUILTIN_SCENARIO_CODE
}

export function scenarioLabel(code: string): string {
  return isBuiltinScenario(code) ? `${code}（内置）` : code
}
