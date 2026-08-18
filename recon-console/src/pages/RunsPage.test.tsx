import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRun, listRuns } from '../api/recon'
import { renderApp } from '../test/render'
import { RunsPage } from './RunsPage'

vi.mock('../api/recon', () => ({
  listRuns: vi.fn(),
  getRun: vi.fn(),
  rerunRun: vi.fn(),
  launchRun: vi.fn(),
}))

const mockedListRuns = vi.mocked(listRuns)
const mockedGetRun = vi.mocked(getRun)

const run = {
  runId: 'MARKETING_3WAY:2026-08-18:1',
  scenarioCode: 'MARKETING_3WAY',
  accountingPeriod: '2026-08-18',
  sequenceNo: 1,
  status: 'COMPLETED',
  bucketCount: 64,
  createdAt: '2026-08-18T10:00:00Z',
  startedAt: '2026-08-18T10:00:00Z',
  finishedAt: '2026-08-18T10:01:00Z',
  discrepancyCount: 1,
  openDiscrepancyCount: 1,
  balanced: true,
}

describe('RunsPage', () => {
  beforeEach(() => {
    mockedListRuns.mockResolvedValue({ content: [run], page: 0, size: 20, totalElements: 1, totalPages: 1 })
    mockedGetRun.mockResolvedValue({
      run,
      reports: [
        {
          segmentId: 'SEG1_MKT_ACCT',
          currency: 'USD',
          expectedTotalMinor: '1000',
          matchedAmountMinor: '900',
          amountMismatchMinor: '100',
          missingMinor: '0',
          duplicateMinor: '0',
          extraMinor: '0',
          timingMinor: '0',
          statusMismatchMinor: '0',
          currencyMismatchMinor: '0',
          groupSumMismatchMinor: '0',
          bridgeBrokenMinor: '0',
          rightSideTotalMinor: '900',
          leftResidualMinor: '0',
          rightResidualMinor: '0',
          balanced: true,
        },
      ],
    })
  })

  it('opens a run detail with conservation report', async () => {
    const user = userEvent.setup()
    renderApp(<RunsPage />)

    const runId = await screen.findByText(run.runId)
    await user.click(runId.closest('button')!)

    expect(await screen.findByText('运行信息')).toBeInTheDocument()
    expect(screen.getByText('守恒报表')).toBeInTheDocument()
    expect(screen.getByText('SEG1_MKT_ACCT')).toBeInTheDocument()
    expect(mockedGetRun).toHaveBeenCalledWith(run.runId)
  })
})
