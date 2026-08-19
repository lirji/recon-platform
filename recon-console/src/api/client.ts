import axios, { type InternalAxiosRequestConfig } from 'axios'
import { AUTH_MODE } from '../auth/config'
import { getAccessToken, renewAccessToken } from '../auth/oidc'

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

// A1: 请求拦截器注入 Bearer(dev 模式无 token → 不加,后端 permitAll)。异步取 token 使 oidc 过期时先静默续期。
api.interceptors.request.use(async (config) => {
  const token = await getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const original = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined
      // A1: 401 且 oidc 模式 → 一次受控静默续期后重放原请求;二次仍失败才落 ApiError(由 AuthContext 清会话跳登录)。
      if (error.response?.status === 401 && AUTH_MODE === 'oidc' && original && !original._retry) {
        original._retry = true
        const renewed = await renewAccessToken()
        if (renewed) {
          original.headers.Authorization = `Bearer ${renewed}`
          return api(original)
        }
      }
      const body = error.response?.data as { error?: string; message?: string } | undefined
      const fallback = error.response ? `请求失败（${error.response.status}）` : '无法连接对账服务'
      return Promise.reject(new ApiError(body?.message || fallback, error.response?.status, body?.error))
    }
    return Promise.reject(error)
  },
)
