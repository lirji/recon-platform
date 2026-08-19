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
    if (url.pathname === `/recon/runs/${encodeURIComponent(run.runId)}`) {
      return json({ run, reports: [] })
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
