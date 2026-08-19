import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listScenarios } from '../api/recon'
import { mockAuth, renderApp } from '../test/render'
import { ScenariosPage } from './ScenariosPage'

vi.mock('../api/recon', () => ({
  listScenarios: vi.fn(),
  getScenario: vi.fn(),
  saveScenario: vi.fn(),
}))

const mockedList = vi.mocked(listScenarios)

describe('ScenariosPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedList.mockResolvedValue([
      { code: 'MARKETING_3WAY', version: 3, enabled: true, segmentCount: 2 },
      { code: 'OFF_ONE', version: 1, enabled: false, segmentCount: 1 },
    ])
  })

  it('renders scenarios with enabled/disabled tags', async () => {
    renderApp(<ScenariosPage />)
    expect(await screen.findByText('MARKETING_3WAY')).toBeInTheDocument()
    expect(screen.getByText('OFF_ONE')).toBeInTheDocument()
    expect(screen.getByText('启用')).toBeInTheDocument()
    expect(screen.getByText('停用')).toBeInTheDocument()
  })

  it('shows the create button for a launcher', async () => {
    renderApp(<ScenariosPage />)
    await screen.findByText('MARKETING_3WAY')
    expect(screen.getByRole('button', { name: /新建场景/ })).toBeInTheDocument()
  })

  it('hides the create button for a read-only viewer', async () => {
    renderApp(<ScenariosPage />, mockAuth({ permissions: ['recon.read'] }))
    await screen.findByText('MARKETING_3WAY')
    expect(screen.queryByRole('button', { name: /新建场景/ })).not.toBeInTheDocument()
  })
})
