import type { PropsWithChildren, ReactElement } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { App, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { appTheme } from '../theme/theme'

export function renderApp(element: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: PropsWithChildren) {
    return (
      <ConfigProvider locale={zhCN} theme={appTheme}>
        <App>
          <QueryClientProvider client={queryClient}>
            <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{children}</MemoryRouter>
          </QueryClientProvider>
        </App>
      </ConfigProvider>
    )
  }
  return { ...render(element, { wrapper: Wrapper }), queryClient }
}
