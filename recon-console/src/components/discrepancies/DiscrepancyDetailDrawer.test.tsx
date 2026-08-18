import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { getDiscrepancy, resolveDiscrepancy } from '../../api/recon'
import { renderApp } from '../../test/render'
import { DiscrepancyDetailDrawer } from './DiscrepancyDetailDrawer'

vi.mock('../../api/recon', () => ({
  getDiscrepancy: vi.fn(),
  resolveDiscrepancy: vi.fn(),
  closeDiscrepancy: vi.fn(),
}))

const mockedGet = vi.mocked(getDiscrepancy)
const mockedResolve = vi.mocked(resolveDiscrepancy)

const discrepancy = {
  discrepancyId: 'disc-1',
  runId: 'run-1',
  scenarioCode: 'MARKETING_3WAY',
  accountingPeriod: '2026-08-18',
  segmentId: 'SEG1_MKT_ACCT',
  type: 'AMOUNT_MISMATCH',
  bridgeBreakStage: null,
  fingerprint: 'F'.repeat(64),
  groupKey: 'ORDER-42',
  matchKey: 'ISSUE-42',
  currency: 'USD',
  expectedAmountMinor: '1000',
  actualAmountMinor: '900',
  deltaAmountMinor: '100',
  leftRawRef: 'marketing:42',
  rightRawRef: 'accounting:42',
  dispositionStatus: 'OPEN',
  operator: null,
  note: null,
  dispositionVersion: null,
  createdAt: '2026-08-18T10:00:00Z',
  updatedAt: '2026-08-18T10:00:00Z',
}

describe('DiscrepancyDetailDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
    mockedGet.mockResolvedValue({ discrepancy, actions: [], reversals: [], alerts: [] })
    mockedResolve.mockResolvedValue({
      fingerprint: discrepancy.fingerprint,
      segmentId: discrepancy.segmentId,
      status: 'RESOLVED',
      operator: 'ops-user',
      note: 'verified',
      version: 0,
      lastSeenRunId: discrepancy.runId,
    })
  })

  it('submits a manual resolution with the current optimistic-lock version', async () => {
    const user = userEvent.setup()
    renderApp(<DiscrepancyDetailDrawer discrepancyId="disc-1" onClose={() => undefined} />)

    await user.click(await screen.findByRole('button', { name: /核销/ }))
    await user.type(screen.getByLabelText('操作人'), 'ops-user')
    await user.type(screen.getByPlaceholderText('记录核验依据或关闭原因'), 'verified')
    await user.click(screen.getByRole('button', { name: /确认核销/ }))

    await waitFor(() => expect(mockedResolve).toHaveBeenCalledWith('disc-1', {
      operator: 'ops-user',
      note: 'verified',
      expectedVersion: undefined,
    }))
  })

  it('refreshes and shows a recoverable message on an optimistic-lock conflict', async () => {
    const user = userEvent.setup()
    mockedResolve.mockRejectedValueOnce(new ApiError('version conflict', 409, 'conflict'))
    renderApp(<DiscrepancyDetailDrawer discrepancyId="disc-1" onClose={() => undefined} />)

    await user.click(await screen.findByRole('button', { name: /核销/ }))
    await user.type(screen.getByLabelText('操作人'), 'ops-user')
    await user.click(screen.getByRole('button', { name: /确认核销/ }))

    expect(await screen.findByText('处置状态已被其他操作更新，已为你刷新详情')).toBeInTheDocument()
    await waitFor(() => expect(mockedGet).toHaveBeenCalledTimes(2))
  })
})
