import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getDashboard } from '../api/recon'
import { renderApp } from '../test/render'
import { DashboardPage } from './DashboardPage'

vi.mock('../api/recon', () => ({
  getDashboard: vi.fn(),
  getRun: vi.fn(),
  rerunRun: vi.fn(),
  launchRun: vi.fn(),
}))

vi.mock('../components/dashboard/DiscrepancyPieChart', () => ({
  DiscrepancyPieChart: () => <div aria-label="差异类型构成图">chart</div>,
}))

const mockedDashboard = vi.mocked(getDashboard)

describe('DashboardPage', () => {
  beforeEach(() => {
    mockedDashboard.mockResolvedValue({
      metrics: {
        totalRuns: 12,
        runningRuns: 2,
        completedRuns: 8,
        failedRuns: 1,
        imbalancedRuns: 1,
        openDiscrepancies: 6,
        resolvedDiscrepancies: 4,
        closedDiscrepancies: 2,
      },
      discrepancyTypes: [{ key: 'AMOUNT_MISMATCH', count: 5 }],
      recentRuns: [
        {
          runId: 'MARKETING_3WAY:2026-08-18:1',
          scenarioCode: 'MARKETING_3WAY',
          accountingPeriod: '2026-08-18',
          sequenceNo: 1,
          status: 'COMPLETED',
          bucketCount: 64,
          createdAt: '2026-08-18T10:00:00Z',
          startedAt: '2026-08-18T10:00:00Z',
          finishedAt: '2026-08-18T10:01:00Z',
          discrepancyCount: 5,
          openDiscrepancyCount: 2,
          balanced: true,
        },
      ],
    })
  })

  it('renders operational metrics, chart and latest run', async () => {
    renderApp(<DashboardPage />)

    expect(await screen.findByText('对账运营总览')).toBeInTheDocument()
    expect(screen.getByLabelText('累计运行')).toHaveTextContent('12')
    expect(screen.getByLabelText('待处理差异')).toHaveTextContent('6')
    expect(await screen.findByLabelText('差异类型构成图')).toBeInTheDocument()
    expect(screen.getByText('MARKETING_3WAY:2026-08-18:1')).toBeInTheDocument()
  })
})
