import { screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { ApiError } from '../../api/client'
import { getThreeWayReport } from '../../api/recon'
import type { CurrencyRollup, ReportEntry, ThreeWayReport } from '../../api/types'
import { renderApp } from '../../test/render'
import { ThreeWayRollupPanel } from './ThreeWayRollupPanel'

vi.mock('../../api/recon', () => ({
  getThreeWayReport: vi.fn(),
}))

const mockedGet = vi.mocked(getThreeWayReport)

function seg(overrides: Partial<ReportEntry> = {}): ReportEntry {
  return {
    segmentId: 'SEG1_MKT_ACCT',
    currency: 'USD',
    expectedTotalMinor: '1000',
    matchedAmountMinor: '1000',
    amountMismatchMinor: '0',
    missingMinor: '0',
    duplicateMinor: '0',
    extraMinor: '0',
    timingMinor: '0',
    statusMismatchMinor: '0',
    currencyMismatchMinor: '0',
    groupSumMismatchMinor: '0',
    bridgeBrokenMinor: '0',
    rightSideTotalMinor: '1000',
    leftResidualMinor: '0',
    rightResidualMinor: '0',
    balanced: true,
    ...overrides,
  }
}

function rollup(overrides: Partial<CurrencyRollup> = {}): CurrencyRollup {
  return {
    currency: 'USD',
    seg1: seg({ segmentId: 'SEG1_MKT_ACCT' }),
    seg2: seg({ segmentId: 'SEG2_ACCT_CHANNEL' }),
    threeWayConsistent: true,
    bridgeBrokenMinor: '0',
    ...overrides,
  }
}

function report(overrides: Partial<ThreeWayReport> = {}): ThreeWayReport {
  return {
    runId: 'MARKETING_3WAY:2026-08-18:1',
    scenarioCode: 'MARKETING_3WAY',
    accountingPeriod: '2026-08-18',
    status: 'COMPLETED',
    threeWayBalanced: true,
    currencies: [rollup()],
    ...overrides,
  }
}

describe('ThreeWayRollupPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedGet.mockResolvedValue(report())
  })

  it('renders a balanced banner when all currencies are consistent', async () => {
    renderApp(<ThreeWayRollupPanel runId="run-1" enabled />)
    expect(await screen.findByText('三方守恒 · 全平')).toBeInTheDocument()
  })

  it('renders an inconsistent banner with the count of failing currencies', async () => {
    mockedGet.mockResolvedValue(
      report({
        threeWayBalanced: false,
        currencies: [
          rollup({ currency: 'USD', threeWayConsistent: true }),
          rollup({ currency: 'EUR', threeWayConsistent: false, seg2: seg({ segmentId: 'SEG2_ACCT_CHANNEL', balanced: false }) }),
        ],
      }),
    )
    renderApp(<ThreeWayRollupPanel runId="run-1" enabled />)
    expect(await screen.findByText('三方不一致')).toBeInTheDocument()
    expect(screen.getByText(/存在 1 个币种/)).toBeInTheDocument()
  })

  it('renders an empty state when there is no three-way report', async () => {
    mockedGet.mockResolvedValue(report({ threeWayBalanced: null, currencies: [] }))
    renderApp(<ThreeWayRollupPanel runId="run-1" enabled />)
    expect(await screen.findByText('该 Run 无三方链路报表')).toBeInTheDocument()
    expect(screen.queryByText('三方守恒 · 全平')).not.toBeInTheDocument()
  })

  it('marks a missing segment as an incomplete link without crashing', async () => {
    mockedGet.mockResolvedValue(
      report({ threeWayBalanced: false, currencies: [rollup({ threeWayConsistent: false, seg2: null })] }),
    )
    renderApp(<ThreeWayRollupPanel runId="run-1" enabled />)
    expect(await screen.findByText(/链路不完整/)).toBeInTheDocument()
    // 存在的那一段仍展示。
    expect(screen.getByText('SEG1 营销 ↔ 账务')).toBeInTheDocument()
  })

  it('renders large amounts as strings without losing precision', async () => {
    const big = '9007199254740993' // > Number.MAX_SAFE_INTEGER
    mockedGet.mockResolvedValue(
      report({
        threeWayBalanced: false,
        currencies: [
          rollup({
            threeWayConsistent: false,
            bridgeBrokenMinor: big,
            seg1: seg({ segmentId: 'SEG1_MKT_ACCT', bridgeBrokenMinor: big, balanced: false }),
          }),
        ],
      }),
    )
    renderApp(<ThreeWayRollupPanel runId="run-1" enabled />)
    await screen.findByText('三方不一致')
    const text = document.body.textContent || ''
    expect(text).toContain('9,007,199,254,740,993')
    expect(text).not.toMatch(/e\+/i) // 无科学计数
    expect(text).not.toContain('740992') // 无 number 截断
  })

  it('shows a bridge-broken drill-down link scoped to the segment', async () => {
    mockedGet.mockResolvedValue(
      report({
        threeWayBalanced: false,
        currencies: [
          rollup({
            threeWayConsistent: false,
            bridgeBrokenMinor: '500',
            seg1: seg({ segmentId: 'SEG1_MKT_ACCT', bridgeBrokenMinor: '500', balanced: false }),
          }),
        ],
      }),
    )
    renderApp(<ThreeWayRollupPanel runId="MARKETING_3WAY:2026-08-18:1" enabled />)
    const link = await screen.findByRole('link', { name: '查看桥断差异' })
    const href = link.getAttribute('href') || ''
    expect(href).toContain('/discrepancies?')
    expect(href).toContain('segmentId=SEG1_MKT_ACCT')
    expect(href).toContain('type=BRIDGE_BROKEN')
    expect(href).toContain(encodeURIComponent('MARKETING_3WAY:2026-08-18:1'))
  })

  it('shows a skeleton while loading', async () => {
    mockedGet.mockReturnValue(new Promise<ThreeWayReport>(() => {}))
    renderApp(<ThreeWayRollupPanel runId="run-1" enabled />)
    expect(await screen.findByLabelText('正在加载')).toBeInTheDocument()
  })

  it('shows an error state and retries', async () => {
    mockedGet.mockRejectedValueOnce(new ApiError('boom', 500, 'error'))
    renderApp(<ThreeWayRollupPanel runId="run-1" enabled />)
    const user = userEvent.setup()
    expect(await screen.findByText('页面加载失败')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /重试/ }))
    await waitFor(() => expect(mockedGet).toHaveBeenCalledTimes(2))
  })

  it('does not fetch when disabled', async () => {
    renderApp(<ThreeWayRollupPanel runId="run-1" enabled={false} />)
    await waitFor(() => expect(screen.getByText('该 Run 无三方链路报表')).toBeInTheDocument())
    expect(mockedGet).not.toHaveBeenCalled()
  })
})
