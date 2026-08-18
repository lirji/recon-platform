import axios from 'axios'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export const api = axios.create({
  baseURL: import.meta.env.VITE_RECON_API_BASE || '',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const body = error.response?.data as { error?: string; message?: string } | undefined
      const fallback = error.response ? `请求失败（${error.response.status}）` : '无法连接对账服务'
      return Promise.reject(new ApiError(body?.message || fallback, error.response?.status, body?.error))
    }
    return Promise.reject(error)
  },
)
