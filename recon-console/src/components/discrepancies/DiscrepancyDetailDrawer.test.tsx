import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { executeReversal, getDiscrepancy, getGroupRecords, resolveDiscrepancy } from '../../api/recon'
import { mockAuth, renderApp } from '../../test/render'
import { DiscrepancyDetailDrawer } from './DiscrepancyDetailDrawer'

vi.mock('../../api/recon', () => ({
  getMe: vi.fn(),
  getDiscrepancy: vi.fn(),
  resolveDiscrepancy: vi.fn(),
  closeDiscrepancy: vi.fn(),
  submitReversalApproval: vi.fn(),
  executeReversal: vi.fn(),
  getGroupRecords: vi.fn(),
  proposeRemediation: vi.fn(),
}))

const mockedGet = vi.mocked(getDiscrepancy)
const mockedResolve = vi.mocked(resolveDiscrepancy)
const mockedExecute = vi.mocked(executeReversal)
const mockedRecords = vi.mocked(getGroupRecords)

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
    mockedRecords.mockResolvedValue({
      runId: discrepancy.runId,
      segmentId: discrepancy.segmentId,
      groupKey: discrepancy.groupKey!,
      recordCount: 1,
      truncated: false,
      records: [
        {
          recordId: 'r-l1',
          side: 'LEFT',
          sourceRole: 'MARKETING',
          matchKey: 'ISSUE-42',
          currency: 'USD',
          signedAmountMinor: '1000',
          entryType: 'ISSUE',
          bizStatus: 'PAID',
          rawRef: 'marketing:42',
        },
      ],
    })
    mockedExecute.mockResolvedValue({ reversalId: 'rev-1', status: 'EXECUTED', executed: true, reference: 'REF-1' })
    mockedGet.mockResolvedValue({ discrepancy, actions: [], reversals: [], alerts: [] })
    mockedResolve.mockResolvedValue({
      fingerprint: discrepancy.fingerprint,
      segmentId: discrepancy.segmentId,
      status: 'RESOLVED',
      operator: 'qa-ops',
      note: 'verified',
      version: 0,
      lastSeenRunId: discrepancy.runId,
    })
  })

  it('hides dispose actions for a viewer without recon.dispose', async () => {
    // 放在最前:避免后续 admin 用例的 Drawer portal 残留干扰。
    renderApp(
      <DiscrepancyDetailDrawer discrepancyId="disc-1" onClose={() => undefined} />,
      mockAuth({ permissions: ['recon.read'] }),
    )
    await waitFor(() => expect(mockedGet).toHaveBeenCalled())
    // 观察员看不到核销动作按钮('核销' 精确名,不与 Drawer 自带关闭 X 冲突)。
    await waitFor(() => expect(screen.queryByRole('button', { name: '核销' })).not.toBeInTheDocument())
  })

  it('submits a manual resolution with operator from the session identity (not a manual field)', async () => {
    const user = userEvent.setup()
    renderApp(<DiscrepancyDetailDrawer discrepancyId="disc-1" onClose={() => undefined} />)

    await user.click(await screen.findByRole('button', { name: /核销/ }))
    // 无手填操作人输入框:operator 取自登录身份(mockAuth name=qa-ops)。
    expect(screen.queryByLabelText('操作人')).not.toBeInTheDocument()
    await user.type(screen.getByPlaceholderText('记录核验依据或关闭原因'), 'verified')
    await user.click(screen.getByRole('button', { name: /确认核销/ }))

    await waitFor(() => expect(mockedResolve).toHaveBeenCalledWith('disc-1', {
      operator: 'qa-ops',
      note: 'verified',
      expectedVersion: undefined,
    }))
  })

  it('refreshes and shows a recoverable message on an optimistic-lock conflict', async () => {
    const user = userEvent.setup()
    mockedResolve.mockRejectedValueOnce(new ApiError('version conflict', 409, 'conflict'))
    renderApp(<DiscrepancyDetailDrawer discrepancyId="disc-1" onClose={() => undefined} />)

    await user.click(await screen.findByRole('button', { name: /核销/ }))
    await user.click(screen.getByRole('button', { name: /确认核销/ }))

    expect(await screen.findByText('处置状态已被其他操作更新，已为你刷新详情')).toBeInTheDocument()
    await waitFor(() => expect(mockedGet).toHaveBeenCalledTimes(2))
  })

  it('loads group records and can execute a confirmed reversal', async () => {
    const user = userEvent.setup()
    mockedGet.mockResolvedValue({
      discrepancy,
      actions: [],
      reversals: [
        {
          id: 'rev-1',
          runId: discrepancy.runId,
          groupKey: 'ORDER-42',
          suggestedAmountMinor: '100',
          currency: 'USD',
          status: 'CONFIRMED',
          operator: 'qa-ops',
          createdAt: '2026-08-18T10:00:00Z',
        },
      ],
      alerts: [],
    })
    renderApp(<DiscrepancyDetailDrawer discrepancyId="disc-1" onClose={() => undefined} />)

    await user.click(await screen.findByText('组内明细'))
    await waitFor(() => expect(mockedRecords).toHaveBeenCalledWith('run-1', 'SEG1_MKT_ACCT', 'ORDER-42'))

    await user.click(await screen.findByText(/冲正建议/))
    expect(await screen.findByText('已通过')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '执行冲正' }))
    await user.click(await screen.findByRole('button', { name: '确认执行' }))
    await waitFor(() => expect(mockedExecute).toHaveBeenCalledWith('rev-1', 'qa-ops'))
  })

  it('hides the expected-source block when ODS fields are absent', async () => {
    renderApp(<DiscrepancyDetailDrawer discrepancyId="disc-1" onClose={() => undefined} />)
    expect(await screen.findByText('disc-1')).toBeInTheDocument()
    expect(screen.queryByText('应发源')).not.toBeInTheDocument()
    expect(screen.queryByText('营销请求号')).not.toBeInTheDocument()
  })

  it('renders expected-source fields only when the read model provides them', async () => {
    mockedGet.mockResolvedValue({
      discrepancy: {
        ...discrepancy,
        expectedSourceSystem: 'marketing-lowcode',
        marketingSourceRequestId: 'src-42',
        benefitOrderNo: 'ORD-42',
      },
      actions: [],
      reversals: [],
      alerts: [],
    })
    renderApp(<DiscrepancyDetailDrawer discrepancyId="disc-1" onClose={() => undefined} />)
    expect(await screen.findByText('应发源')).toBeInTheDocument()
    expect(screen.getByText('marketing-lowcode')).toBeInTheDocument()
    expect(screen.getByText('src-42')).toBeInTheDocument()
    expect(screen.getByText('ORD-42')).toBeInTheDocument()
  })

  it('hides execute for a viewer without recon.launch', async () => {
    mockedGet.mockResolvedValue({
      discrepancy,
      actions: [],
      reversals: [
        {
          id: 'rev-1',
          runId: discrepancy.runId,
          groupKey: 'ORDER-42',
          suggestedAmountMinor: '100',
          currency: 'USD',
          status: 'CONFIRMED',
          operator: null,
          createdAt: '2026-08-18T10:00:00Z',
        },
      ],
      alerts: [],
    })
    renderApp(
      <DiscrepancyDetailDrawer discrepancyId="disc-1" onClose={() => undefined} />,
      mockAuth({ permissions: ['recon.read', 'recon.dispose'] }),
    )
    const user = userEvent.setup()
    await user.click(await screen.findByText(/冲正建议/))
    expect(screen.queryByRole('button', { name: '执行冲正' })).not.toBeInTheDocument()
  })
})
