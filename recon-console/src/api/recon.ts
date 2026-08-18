import { api } from './client'
import type {
  ClearRequest,
  DashboardView,
  DiscrepancyDetail,
  DiscrepancyFilters,
  DiscrepancySummary,
  DispositionResponse,
  LaunchResult,
  LaunchRunRequest,
  PageResult,
  RunDetail,
  RunFilters,
  RunSummary,
} from './types'

export async function getDashboard(): Promise<DashboardView> {
  return (await api.get<DashboardView>('/recon/dashboard')).data
}

export async function listRuns(filters: RunFilters): Promise<PageResult<RunSummary>> {
  return (await api.get<PageResult<RunSummary>>('/recon/runs', { params: filters })).data
}

export async function getRun(runId: string): Promise<RunDetail> {
  return (await api.get<RunDetail>(`/recon/runs/${encodeURIComponent(runId)}`)).data
}

export async function launchRun(request: LaunchRunRequest): Promise<LaunchResult> {
  return (await api.post<LaunchResult>('/recon/runs', request)).data
}

export async function rerunRun(runId: string): Promise<LaunchResult> {
  return (await api.post<LaunchResult>(`/recon/runs/${encodeURIComponent(runId)}/rerun`)).data
}

export async function listDiscrepancies(
  filters: DiscrepancyFilters,
): Promise<PageResult<DiscrepancySummary>> {
  return (await api.get<PageResult<DiscrepancySummary>>('/recon/discrepancies', { params: filters })).data
}

export async function getDiscrepancy(discrepancyId: string): Promise<DiscrepancyDetail> {
  return (await api.get<DiscrepancyDetail>(`/recon/discrepancies/${encodeURIComponent(discrepancyId)}`)).data
}

export async function resolveDiscrepancy(
  discrepancyId: string,
  request: ClearRequest,
): Promise<DispositionResponse> {
  return (
    await api.post<DispositionResponse>(
      `/recon/discrepancies/${encodeURIComponent(discrepancyId)}/resolve`,
      request,
    )
  ).data
}

export async function closeDiscrepancy(
  discrepancyId: string,
  request: ClearRequest,
): Promise<DispositionResponse> {
  return (
    await api.post<DispositionResponse>(
      `/recon/discrepancies/${encodeURIComponent(discrepancyId)}/close`,
      request,
    )
  ).data
}
