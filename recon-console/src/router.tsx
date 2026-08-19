import { Suspense, lazy, type ReactNode } from 'react'
import { Navigate, Outlet, createBrowserRouter } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { PageSkeleton } from './components/common/AsyncState'
import { AuthProvider } from './auth/AuthContext'
import { RequireAuth, RequireGuest } from './auth/RequireAuth'

// 路由级代码分割: 每个页面(及其抽屉/弹窗依赖)拆成独立 chunk, 仅在进入该路由时按需加载。
const DashboardPage = lazy(() => import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })))
const RunsPage = lazy(() => import('./pages/RunsPage').then((m) => ({ default: m.RunsPage })))
const DiscrepanciesPage = lazy(() => import('./pages/DiscrepanciesPage').then((m) => ({ default: m.DiscrepanciesPage })))
const LoginPage = lazy(() => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })))
const CallbackPage = lazy(() => import('./pages/CallbackPage').then((m) => ({ default: m.CallbackPage })))
const ForbiddenPage = lazy(() => import('./pages/ForbiddenPage').then((m) => ({ default: m.ForbiddenPage })))

const lazyRoute = (node: ReactNode): ReactNode => <Suspense fallback={<PageSkeleton />}>{node}</Suspense>

// A1: AuthProvider 作路由根元素(RouterProvider 不接 children),使 login/callback/守卫都能用 router hooks 与会话态。
function AuthLayout() {
  return (
    <AuthProvider>
      <Outlet />
    </AuthProvider>
  )
}

export const router = createBrowserRouter([
  {
    element: <AuthLayout />,
    children: [
      // 公共路由(AppLayout 之外):登录 / 授权码回调 / 无权限。
      { path: 'login', element: <RequireGuest />, children: [{ index: true, element: lazyRoute(<LoginPage />) }] },
      { path: 'auth/callback', element: lazyRoute(<CallbackPage />) },
      { path: 'forbidden', element: lazyRoute(<ForbiddenPage />) },
      // 受保护:整个运营台需已认证 + recon.read。
      {
        path: '/',
        element: <RequireAuth permission="recon.read" />,
        children: [
          {
            element: <AppLayout />,
            children: [
              { index: true, element: <Navigate to="/dashboard" replace /> },
              { path: 'dashboard', element: lazyRoute(<DashboardPage />) },
              { path: 'runs', element: lazyRoute(<RunsPage />) },
              { path: 'discrepancies', element: lazyRoute(<DiscrepanciesPage />) },
            ],
          },
        ],
      },
      { path: '*', element: <Navigate to="/dashboard" replace /> },
    ],
  },
])
