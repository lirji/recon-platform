import type { PropsWithChildren, ReactElement } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { appTheme } from '../theme/theme'
import { AuthContext, type AuthContextValue } from '../auth/AuthContext'
import type { UserSession } from '../api/types'

// A1: 测试注入固定会话(默认 admin 全权限),让写控件可见、useAuth 不报错;可覆盖 permissions 测角色门控。
const ALL_PERMISSIONS = ['recon.read', 'recon.dispose', 'recon.launch']

export function mockAuth(overrides: Partial<UserSession> = {}): AuthContextValue {
  const user: UserSession = {
    authenticated: true,
    sub: 'test-user',
    name: 'qa-ops',
    permissions: ALL_PERMISSIONS,
    ...overrides,
  }
  return {
    user,
    loaded: true,
    redirecting: false,
    error: '',
    authenticated: user.authenticated,
    can: (permission?: string) => !permission || user.permissions.includes(permission),
    load: async () => {},
    login: async () => {},
    completeLogin: async () => '/dashboard',
    logout: async () => {},
  }
}

export function renderApp(element: ReactElement, auth: AuthContextValue = mockAuth()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: PropsWithChildren) {
    return (
      <ConfigProvider locale={zhCN} theme={appTheme}>
        <App>
          <QueryClientProvider client={queryClient}>
            <AuthContext.Provider value={auth}>
              <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{children}</MemoryRouter>
            </AuthContext.Provider>
          </QueryClientProvider>
        </App>
      </ConfigProvider>
    )
  }
  return { ...render(element, { wrapper: Wrapper }), queryClient }
}
