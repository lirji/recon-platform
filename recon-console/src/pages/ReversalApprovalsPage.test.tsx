import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { decideReversalApproval, listReversalApprovals } from '../api/recon'
import type { PendingApprovalView } from '../api/types'
import { mockAuth, renderApp } from '../test/render'
import { ReversalApprovalsPage } from './ReversalApprovalsPage'

vi.mock('../api/recon', () => ({
  listReversalApprovals: vi.fn(),
  decideReversalApproval: vi.fn(),
}))

const mockedList = vi.mocked(listReversalApprovals)
const mockedDecide = vi.mocked(decideReversalApproval)

const pending: PendingApprovalView = {
  taskId: 'tk-1',
  reversalId: 'rev-1',
  createdAt: '2026-08-19T10:00:00Z',
  suggestedAmountMinor: '1234',
  currency: 'USD',
  status: 'SUGGESTED',
  groupKey: 'G1',
  runId: 'run-1',
}

describe('ReversalApprovalsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedList.mockResolvedValue([pending])
    mockedDecide.mockResolvedValue(undefined)
  })

  it('renders pending approvals with amount and status', async () => {
    renderApp(<ReversalApprovalsPage />)
    expect(await screen.findByText('rev-1')).toBeInTheDocument()
    expect(screen.getByText('USD 1,234')).toBeInTheDocument()
    expect(screen.getByText('待审批')).toBeInTheDocument()
  })

  it('shows a dedicated error when the workflow is disabled (409 illegal_transition)', async () => {
    mockedList.mockRejectedValue(new ApiError('disabled', 409, 'illegal_transition'))
    renderApp(<ReversalApprovalsPage />)
    expect(await screen.findByText(/审批工作流未启用/)).toBeInTheDocument()
  })

  it('shows an empty state when there is nothing to approve', async () => {
    mockedList.mockResolvedValue([])
    renderApp(<ReversalApprovalsPage />)
    expect(await screen.findByText(/还没有可展示的数据/)).toBeInTheDocument()
  })

  it('hides approve/reject actions for a read-only viewer', async () => {
    renderApp(<ReversalApprovalsPage />, mockAuth({ permissions: ['recon.read'] }))
    await screen.findByText('rev-1')
    expect(screen.queryByRole('button', { name: /通过/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /驳回/ })).not.toBeInTheDocument()
  })

  it('approves with a required note', async () => {
    const user = userEvent.setup()
    renderApp(<ReversalApprovalsPage />)
    await screen.findByText('rev-1')

    await user.click(screen.getByRole('button', { name: /通过/ }))
    expect(await screen.findByText('通过审批')).toBeInTheDocument()

    await user.type(screen.getByPlaceholderText(/必填/), '金额核对无误')
    await user.click(screen.getByRole('button', { name: /确认通过/ }))

    await waitFor(() => expect(mockedDecide).toHaveBeenCalled())
    const call = mockedDecide.mock.calls[0]
    expect(call[0]).toBe('tk-1')
    expect(call[1]).toBe(true)
    expect(call[3]).toBe('金额核对无误')
  })

  it('blocks submission when the note is empty', async () => {
    const user = userEvent.setup()
    renderApp(<ReversalApprovalsPage />)
    await screen.findByText('rev-1')

    await user.click(screen.getByRole('button', { name: /通过/ }))
    await screen.findByText('通过审批')
    await user.click(screen.getByRole('button', { name: /确认通过/ }))

    expect(await screen.findByText('请填写审批意见')).toBeInTheDocument()
    expect(mockedDecide).not.toHaveBeenCalled()
  })
})
