import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { approveRemediation, listRemediations, proposeRemediation } from '../api/recon'
import type { RemediationView } from '../api/types'
import { mockAuth, renderApp } from '../test/render'
import { BenefitRemediationsPage } from './BenefitRemediationsPage'

vi.mock('../api/recon', () => ({
  listRemediations: vi.fn(),
  proposeRemediation: vi.fn(),
  approveRemediation: vi.fn(),
  rejectRemediation: vi.fn(),
}))

const mockedList = vi.mocked(listRemediations)
const mockedApprove = vi.mocked(approveRemediation)
const mockedPropose = vi.mocked(proposeRemediation)

const row: RemediationView = {
  tenantId: 'recon-platform',
  suggestionId: 'sug-1',
  scenarioCode: 'ENTITLEMENT_FULFILLMENT',
  discrepancyRef: 'DISC-1',
  awardItemNo: 'ITEM-1',
  originalOperationNo: 'OP-1',
  action: 'REISSUE',
  reason: 'provider proves not issued',
  status: 'PROPOSED',
  approvalRef: null,
  version: 0,
  createdAt: '2026-08-21T00:00:00Z',
  updatedAt: '2026-08-21T00:00:00Z',
}

describe('BenefitRemediationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedList.mockResolvedValue({ content: [row], page: 0, size: 20, totalElements: 1, totalPages: 1 })
    mockedApprove.mockResolvedValue({ ...row, status: 'APPROVED', version: 1 })
    mockedPropose.mockResolvedValue(row)
  })

  it('does not query remediations with the login organization as a business tenant', async () => {
    renderApp(<BenefitRemediationsPage />)
    expect(await screen.findByText(/请先填写货主业务租户/)).toBeInTheDocument()
    expect(mockedList).not.toHaveBeenCalled()
  })

  it('renders remediations and hides write actions for a viewer', async () => {
    renderApp(<BenefitRemediationsPage />, mockAuth({ permissions: ['recon.read'] }), ['/?tenantId=recon-platform'])
    expect(await screen.findByText('sug-1')).toBeInTheDocument()
    expect(screen.getByText('补发')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /批准/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /提出建议/ })).not.toBeInTheDocument()
  })

  it('approves a proposed reissue with a required approval ref', async () => {
    const user = userEvent.setup()
    renderApp(<BenefitRemediationsPage />, undefined, ['/?tenantId=recon-platform'])
    await screen.findByText('sug-1')
    await user.click(screen.getByRole('button', { name: /批准/ }))
    await user.type(screen.getByLabelText('审批引用'), 'APPROVAL-1')
    await user.click(screen.getByRole('button', { name: /确认批准/ }))
    await waitFor(() => expect(mockedApprove).toHaveBeenCalledWith('sug-1', {
      tenantId: 'recon-platform',
      approvalRef: 'APPROVAL-1',
    }))
  })

  it('can propose a manual-review suggestion', async () => {
    const user = userEvent.setup()
    renderApp(<BenefitRemediationsPage />, undefined, ['/?tenantId=retail-cn'])
    await user.click(await screen.findByRole('button', { name: /提出建议/ }))
    expect(screen.getByLabelText('建议货主业务租户')).toHaveValue('retail-cn')
    await user.type(screen.getByLabelText('场景'), 'ENTITLEMENT_FULFILLMENT')
    await user.type(screen.getByLabelText('差异引用'), 'DISC-NEW')
    await user.type(screen.getByLabelText('权益发放项'), 'ITEM-NEW')
    await user.type(screen.getByLabelText('原因'), 'need human review')
    await user.click(screen.getByRole('button', { name: /提交建议/ }))
    await waitFor(() => expect(mockedPropose).toHaveBeenCalledWith(expect.objectContaining({
      tenantId: 'retail-cn',
      action: 'MANUAL_REVIEW',
      discrepancyRef: 'DISC-NEW',
      awardItemNo: 'ITEM-NEW',
    })))
  })
})
