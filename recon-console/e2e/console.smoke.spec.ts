import { expect, test, type Page } from '@playwright/test'

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

const discrepancy = {
  discrepancyId: 'disc-1',
  runId: run.runId,
  scenarioCode: run.scenarioCode,
  accountingPeriod: run.accountingPeriod,
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

function segReport(segmentId: string, over: Partial<Record<string, unknown>> = {}) {
  return {
    segmentId,
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
    ...over,
  }
}

const threeWay = {
  runId: run.runId,
  scenarioCode: run.scenarioCode,
  accountingPeriod: run.accountingPeriod,
  status: run.status,
  threeWayBalanced: false,
  currencies: [
    {
      currency: 'USD',
      seg1: segReport('SEG1_MKT_ACCT', { bridgeBrokenMinor: '500', balanced: false }),
      seg2: segReport('SEG2_ACCT_CHANNEL'),
      threeWayConsistent: false,
      bridgeBrokenMinor: '500',
    },
  ],
}

const scenarioDefinition = {
  code: 'MARKETING_3WAY',
  segments: [
    { id: 'SEG1', leftRole: 'MARKETING', rightRole: 'ACCOUNTING', spineRole: 'ACCOUNTING', stageLabel: 'SEG1', matchKeyField: 'issueId', groupKeyField: 'orderNo', left: { sourceType: 'db', params: { table: 'recon_src_marketing' } }, right: { sourceType: 'db', params: { table: 'recon_src_accounting' } }, rule: { evaluatorType: 'EXACT', absToleranceMinor: 0, ratioToleranceBps: 0, enabledTypes: null } },
    { id: 'SEG2', leftRole: 'ACCOUNTING', rightRole: 'CHANNEL', spineRole: 'ACCOUNTING', stageLabel: 'SEG2', matchKeyField: 'channelSerialNo', groupKeyField: 'channelSerialNo', left: { sourceType: 'db', params: { table: 'recon_src_accounting' } }, right: { sourceType: 'db', params: { table: 'recon_src_channel' } }, rule: { evaluatorType: 'EXACT', absToleranceMinor: 0, ratioToleranceBps: 0, enabledTypes: null } },
  ],
}

async function mockApi(page: Page) {
  await page.route('**/recon/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    if (url.pathname === '/recon/auth/me') {
      // dev 身份(全权限):让守卫放行、写控件可见。
      return json({ authenticated: true, sub: 'dev', name: 'qa-ops', permissions: ['recon.read', 'recon.dispose', 'recon.launch'] })
    }
    if (url.pathname === '/recon/dashboard') {
      return json({
        metrics: { totalRuns: 1, runningRuns: 0, completedRuns: 1, failedRuns: 0, imbalancedRuns: 0, openDiscrepancies: 1, resolvedDiscrepancies: 0, closedDiscrepancies: 0 },
        discrepancyTypes: [{ key: 'AMOUNT_MISMATCH', count: 1 }],
        recentRuns: [run],
      })
    }
    if (url.pathname === '/recon/runs' && request.method() === 'GET') {
      return json({ content: [run], page: 0, size: 20, totalElements: 1, totalPages: 1 })
    }
    if (url.pathname === `/recon/runs/${encodeURIComponent(run.runId)}/three-way`) {
      return json(threeWay)
    }
    if (url.pathname === `/recon/runs/${encodeURIComponent(run.runId)}`) {
      return json({ run, reports: [] })
    }
    if (url.pathname === '/recon/scenarios' && request.method() === 'GET') {
      return json([{ code: 'MARKETING_3WAY', version: 3, enabled: true, segmentCount: 2 }])
    }
    if (url.pathname === '/recon/scenarios/MARKETING_3WAY' && request.method() === 'GET') {
      return json({ code: 'MARKETING_3WAY', version: 3, enabled: true, definition: scenarioDefinition })
    }
    if (url.pathname === '/recon/scenarios/MARKETING_3WAY' && request.method() === 'PUT') {
      return json({ code: 'MARKETING_3WAY', version: 4, enabled: url.searchParams.get('enabled') === 'true', definition: scenarioDefinition })
    }
    if (url.pathname === '/recon/discrepancies' && request.method() === 'GET') {
      return json({ content: [discrepancy], page: 0, size: 20, totalElements: 1, totalPages: 1 })
    }
    if (url.pathname === '/recon/discrepancies/disc-1' && request.method() === 'GET') {
      return json({ discrepancy, actions: [], reversals: [], alerts: [] })
    }
    if (url.pathname === '/recon/discrepancies/disc-1/resolve' && request.method() === 'POST') {
      return json({ fingerprint: discrepancy.fingerprint, segmentId: discrepancy.segmentId, status: 'RESOLVED', operator: 'qa-ops', note: 'checked', version: 0, lastSeenRunId: run.runId })
    }
    if (url.pathname === '/recon/reversal-approvals' && request.method() === 'GET') {
      return json([{ taskId: 'tk-1', reversalId: 'rev-1', createdAt: '2026-08-18T10:00:00Z', suggestedAmountMinor: '100', currency: 'USD', status: 'SUGGESTED', groupKey: 'ORDER-42', runId: run.runId }])
    }
    if (url.pathname.startsWith('/recon/reversal-approvals/') && url.pathname.endsWith('/decide') && request.method() === 'POST') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '' })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not_found","message":"mock route missing"}' })
  })
}

async function navigateByMenu(page: Page, label: string) {
  if ((page.viewportSize()?.width || 1280) < 992) {
    await page.getByRole('button', { name: '打开菜单' }).click()
  }
  await page.getByText(label, { exact: true }).click()
}

test('operator can inspect runs and resolve a discrepancy', async ({ page }) => {
  await mockApi(page)
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: '对账运营总览' })).toBeVisible()
  await expect(page.getByLabel('待处理差异')).toContainText('1')

  await navigateByMenu(page, '运行管理')
  await expect(page.getByRole('heading', { name: '运行管理' })).toBeVisible()
  await expect(page.getByText(run.runId).first()).toBeVisible()

  await navigateByMenu(page, '差异处理')
  await expect(page.getByRole('heading', { name: '差异处理' })).toBeVisible()
  await page.getByText('ORDER-42', { exact: true }).click()
  await expect(page.getByText('差异详情')).toBeVisible()

  await page.getByRole('button', { name: '核销' }).click()
  // operator 取自登录身份,不再手填。
  await page.getByPlaceholder('记录核验依据或关闭原因').fill('checked')
  await page.getByRole('button', { name: /确认核销/ }).click()
  await expect(page.getByText('差异已核销')).toBeVisible()
})

test('operator can open the three-way roll-up and drill into a bridge break', async ({ page }) => {
  await mockApi(page)
  await page.goto('/runs')
  await expect(page.getByRole('heading', { name: '运行管理' })).toBeVisible()

  // 打开运行详情抽屉。
  await page.getByText(run.runId).first().click()
  await expect(page.getByText('运行信息')).toBeVisible()

  // 切到「三方合并」Tab(懒加载),断言不一致结论与按段桥断下钻链接。
  await page.getByRole('tab', { name: '三方合并' }).click()
  await expect(page.getByText('三方不一致')).toBeVisible()
  await expect(page.getByRole('link', { name: '查看桥断差异' })).toBeVisible()
})

test('operator can approve a pending reversal with a required note', async ({ page }) => {
  await mockApi(page)
  await page.goto('/dashboard')
  await navigateByMenu(page, '冲正审批')
  await expect(page.getByRole('heading', { name: '冲正审批' })).toBeVisible()
  await expect(page.getByText('rev-1').first()).toBeVisible()

  await page.getByRole('button', { name: /通过/ }).first().click()
  await expect(page.getByText('通过审批')).toBeVisible()

  const decideReq = page.waitForRequest(
    (r) => r.url().includes('/recon/reversal-approvals/') && r.url().includes('/decide') && r.method() === 'POST',
  )
  await page.getByPlaceholder(/必填/).fill('金额核对无误')
  await page.getByRole('button', { name: /确认通过/ }).click()
  await decideReq
  await expect(page.getByText('已通过审批')).toBeVisible()
})

test('admin can view and edit a config-driven scenario (raw-text submit preserves large numbers)', async ({ page }) => {
  await mockApi(page)
  await page.goto('/dashboard')
  await navigateByMenu(page, '场景管理')
  await expect(page.getByRole('heading', { name: '场景管理' })).toBeVisible()
  await expect(page.getByText('MARKETING_3WAY').first()).toBeVisible()

  // 打开编辑抽屉,JSON 回显
  await page.getByText('MARKETING_3WAY').first().click()
  const textarea = page.getByLabel('场景定义 JSON')
  await expect(textarea).toBeVisible()
  await expect(textarea).toHaveValue(/MARKETING_3WAY/)

  // 编辑为含大额 absToleranceMinor 的定义, 保存 —— 断言 PUT body 原文逐字含大数(证明 axios+网络原样透传)
  const big = '9007199254740993'
  const raw = `{"code":"MARKETING_3WAY","segments":[{"id":"SEG1","leftRole":"MARKETING","rightRole":"ACCOUNTING","spineRole":null,"stageLabel":"SEG1","matchKeyField":"k","groupKeyField":"k","left":{"sourceType":"db","params":{}},"right":{"sourceType":"db","params":{}},"rule":{"evaluatorType":"TOLERANCE","absToleranceMinor":${big},"ratioToleranceBps":0,"enabledTypes":null}}]}`
  const putReq = page.waitForRequest((r) => r.url().includes('/recon/scenarios/MARKETING_3WAY') && r.method() === 'PUT')
  await textarea.fill(raw)
  await page.getByRole('button', { name: /保\s*存/ }).click()
  const body = (await putReq).postData() || ''
  expect(body).toContain(big)
  expect(body).not.toContain('9007199254740992')
  await expect(page.getByText(/场景已保存/)).toBeVisible()
})
