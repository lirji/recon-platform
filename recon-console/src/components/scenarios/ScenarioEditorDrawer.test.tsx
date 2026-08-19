import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { getScenario, saveScenario } from '../../api/recon'
import type { ScenarioView } from '../../api/types'
import { mockAuth, renderApp } from '../../test/render'
import { ScenarioEditorDrawer } from './ScenarioEditorDrawer'

vi.mock('../../api/recon', () => ({
  listScenarios: vi.fn(),
  getScenario: vi.fn(),
  saveScenario: vi.fn(),
}))

const mockedGet = vi.mocked(getScenario)
const mockedSave = vi.mocked(saveScenario)

function view(code: string, enabled: boolean): ScenarioView {
  return {
    code,
    version: 2,
    enabled,
    definition: {
      code,
      segments: [
        {
          id: 'SEG1',
          leftRole: 'MARKETING',
          rightRole: 'ACCOUNTING',
          spineRole: 'ACCOUNTING',
          stageLabel: 'SEG1',
          matchKeyField: 'issueId',
          groupKeyField: 'orderNo',
          left: { sourceType: 'db', params: { table: 't_left' } },
          right: { sourceType: 'db', params: { table: 't_spine' } },
          rule: { evaluatorType: 'EXACT', absToleranceMinor: 0, ratioToleranceBps: 0, enabledTypes: null },
        },
      ],
    },
  }
}

describe('ScenarioEditorDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedGet.mockResolvedValue(view('S1', true))
    mockedSave.mockResolvedValue(view('S1', true))
  })

  it('loads and shows the definition JSON in edit mode', async () => {
    renderApp(<ScenarioEditorDrawer editing={{ mode: 'edit', code: 'S1' }} existingCodes={['S1']} onClose={() => {}} />)
    await waitFor(() => expect(mockedGet).toHaveBeenCalledWith('S1'))
    const textarea = (await screen.findByLabelText('场景定义 JSON')) as HTMLTextAreaElement
    expect(textarea.value).toContain('"code": "S1"')
    expect(textarea.value).toContain('SEG1')
  })

  it('saves with the raw json text and explicit enabled from detail (untouched switch)', async () => {
    const user = userEvent.setup()
    renderApp(<ScenarioEditorDrawer editing={{ mode: 'edit', code: 'S1' }} existingCodes={['S1']} onClose={() => {}} />)
    await screen.findByLabelText('场景定义 JSON')
    await user.click(screen.getByRole('button', { name: /保\s*存/ }))
    // enabled 从 detail(true)播种,不动开关仍传 true(M1)。第2参为原始文本。
    await waitFor(() =>
      expect(mockedSave).toHaveBeenCalledWith('S1', expect.stringContaining('"code": "S1"'), true),
    )
  })

  it('surfaces a 400 validation error without closing', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    mockedSave.mockRejectedValueOnce(new ApiError('segment SEG1 invalid', 400, 'bad_request'))
    renderApp(<ScenarioEditorDrawer editing={{ mode: 'edit', code: 'S1' }} existingCodes={['S1']} onClose={onClose} />)
    await screen.findByLabelText('场景定义 JSON')
    await user.click(screen.getByRole('button', { name: /保\s*存/ }))
    // 错误同时出现在抽屉内 Alert 与 toast,故 findAll(≥1)。
    expect((await screen.findAllByText('segment SEG1 invalid')).length).toBeGreaterThan(0)
    expect(onClose).not.toHaveBeenCalled()
  })

  it('blocks save on invalid json (client-side, no request)', async () => {
    const user = userEvent.setup()
    renderApp(<ScenarioEditorDrawer editing={{ mode: 'new', code: null }} existingCodes={[]} onClose={() => {}} />)
    const textarea = (await screen.findByLabelText('场景定义 JSON')) as HTMLTextAreaElement
    fireEvent.change(textarea, { target: { value: '{ not valid json' } })
    await user.click(screen.getByRole('button', { name: /保\s*存/ }))
    expect(await screen.findByText(/JSON 语法错误/)).toBeInTheDocument()
    expect(mockedSave).not.toHaveBeenCalled()
  })

  it('submits the exact raw text preserving a large absToleranceMinor (precision, submit side)', async () => {
    const user = userEvent.setup()
    const big = '9007199254740993' // > 2^53
    const raw = `{"code":"BIG","segments":[{"id":"SEG1","leftRole":"MARKETING","rightRole":"ACCOUNTING","spineRole":null,"stageLabel":"SEG1","matchKeyField":"k","groupKeyField":"k","left":{"sourceType":"db","params":{}},"right":{"sourceType":"db","params":{}},"rule":{"evaluatorType":"EXACT","absToleranceMinor":${big},"ratioToleranceBps":0,"enabledTypes":null}}]}`
    renderApp(<ScenarioEditorDrawer editing={{ mode: 'new', code: null }} existingCodes={[]} onClose={() => {}} />)
    const textarea = (await screen.findByLabelText('场景定义 JSON')) as HTMLTextAreaElement
    fireEvent.change(textarea, { target: { value: raw } })
    await user.click(screen.getByRole('button', { name: /保\s*存/ }))
    await waitFor(() => expect(mockedSave).toHaveBeenCalled())
    // 提交的第2参是原始文本,逐字含大数(未被 number 化为 ...992)。
    expect(mockedSave.mock.calls[0][1]).toContain(big)
    expect(mockedSave.mock.calls[0][1]).not.toContain('9007199254740992')
  })

  it('hides save and makes json read-only for a viewer', async () => {
    renderApp(
      <ScenarioEditorDrawer editing={{ mode: 'edit', code: 'S1' }} existingCodes={['S1']} onClose={() => {}} />,
      mockAuth({ permissions: ['recon.read'] }),
    )
    const textarea = (await screen.findByLabelText('场景定义 JSON')) as HTMLTextAreaElement
    expect(textarea).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: /保\s*存/ })).not.toBeInTheDocument()
  })
})
