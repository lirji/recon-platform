import { screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listDiscrepancies } from '../api/recon'
import { renderApp } from '../test/render'
import { DiscrepanciesPage } from './DiscrepanciesPage'

vi.mock('../api/recon', () => ({
  listDiscrepancies: vi.fn(),
  getDiscrepancy: vi.fn(),
  resolveDiscrepancy: vi.fn(),
  closeDiscrepancy: vi.fn(),
}))

const mockedList = vi.mocked(listDiscrepancies)

describe('DiscrepanciesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedList.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
  })

  it('seeds filters from URL query (three-way bridge-broken drill-down)', async () => {
    renderApp(
      <DiscrepanciesPage />,
      undefined,
      ['/discrepancies?runId=MARKETING_3WAY:2026-08-18:1&segmentId=SEG1_MKT_ACCT&type=BRIDGE_BROKEN'],
    )

    await waitFor(() =>
      expect(mockedList).toHaveBeenCalledWith(
        expect.objectContaining({
          runId: 'MARKETING_3WAY:2026-08-18:1',
          segmentId: 'SEG1_MKT_ACCT',
          type: 'BRIDGE_BROKEN',
          page: 0,
          size: 20,
        }),
      ),
    )
    // 过滤输入框回显预置值。
    expect(screen.getByDisplayValue('MARKETING_3WAY:2026-08-18:1')).toBeInTheDocument()
  })

  it('applies no seed filter without query params', async () => {
    renderApp(<DiscrepanciesPage />)
    await waitFor(() => expect(mockedList).toHaveBeenCalledWith({ page: 0, size: 20 }))
  })
})
