import { describe, expect, it } from 'vitest'
import { emptySegment, parseScenarioForm, serializeScenarioForm } from './scenarioJson'

describe('scenarioJson', () => {
  it('round-trips a large absToleranceMinor without Number rounding', () => {
    const big = '9007199254740993'
    const form = {
      code: 'BIG',
      segments: [{
        ...emptySegment('SEG1'),
        rule: { evaluatorType: 'TOLERANCE' as const, absToleranceMinor: big, ratioToleranceBps: 0, enabledTypes: null },
      }],
    }
    const text = serializeScenarioForm(form)
    expect(text).toContain(big)
    expect(text).not.toContain('9007199254740992')
    expect(parseScenarioForm(text)?.segments[0].rule.absToleranceMinor).toBe(big)
  })
})
