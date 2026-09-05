import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRefineViolations, getRun, listRunRejects, listRuns, listScenarios } from '../api/recon'
import { renderApp } from '../test/render'
import { RunsPage } from './RunsPage'

vi.mock('../api/recon', () => ({
  listRuns: vi.fn(),
  listScenarios: vi.fn(),
  getRun: vi.fn(),
  getRefineViolations: vi.fn(),
  rerunRun: vi.fn(),
  launchRun: vi.fn(),
  getThreeWayReport: vi.fn(),
  listRunRejects: vi.fn(),
}))

const mockedListRuns = vi.mocked(listRuns)
const mockedListScenarios = vi.mocked(listScenarios)
const mockedGetRun = vi.mocked(getRun)
const mockedRefine = vi.mocked(getRefineViolations)
const mockedRejects = vi.mocked(listRunRejects)

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
    mockedListScenarios.mockResolvedValue([{ code: 'MARKETING_3WAY', version: 1, enabled: true, segmentCount: 2 }])
    mockedRefine.mockResolvedValue({ runId: run.runId, violationCount: 0, truncated: false, violations: [] })
    mockedRejects.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
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
    expect(screen.getAllByText('桥断额').length).toBeGreaterThan(0)
    expect(screen.getAllByText('左残差').length).toBeGreaterThan(0)
  })

  it('shows a refine-violation alert when dirty match keys are reported', async () => {
    const user = userEvent.setup()
    mockedRefine.mockResolvedValue({
      runId: run.runId,
      violationCount: 1,
      truncated: false,
      violations: [{ segmentId: 'SEG1_MKT_ACCT', matchKey: 'MK-DIRTY', distinctGroupCount: 2 }],
    })
    renderApp(<RunsPage />)
    await user.click((await screen.findByText(run.runId)).closest('button')!)
    expect(await screen.findByText('发现函数性 refine 违规')).toBeInTheDocument()
    expect(screen.getByText('MK-DIRTY')).toBeInTheDocument()
  })

  it('opens the reject-row tab for a run', async () => {
    const user = userEvent.setup()
    mockedRejects.mockResolvedValue({
      content: [{
        id: 'rj-1',
        runId: run.runId,
        segmentId: 'SEG1_MKT_ACCT',
        sourceRole: 'MARKETING',
        rawRef: '/tmp/mkt.csv:12',
        reason: 'invalid amount',
        rawPayload: 'bad,row',
        createdAt: '2026-08-18T10:00:00Z',
      }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    })
    renderApp(<RunsPage />)
    await user.click((await screen.findByText(run.runId)).closest('button')!)
    await user.click(await screen.findByRole('tab', { name: '拒绝行' }))
    expect(await screen.findByText('invalid amount')).toBeInTheDocument()
    expect(screen.getByText('/tmp/mkt.csv:12')).toBeInTheDocument()
  })

  it('seeds status filter from the URL', async () => {
    renderApp(<RunsPage />, undefined, ['/runs?status=FAILED'])
    await screen.findByText(run.runId)
    expect(mockedListRuns).toHaveBeenCalledWith(expect.objectContaining({ status: 'FAILED', page: 0, size: 20 }))
  })
})
