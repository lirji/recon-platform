import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { PageSkeleton } from '../components/common/AsyncState'
import { useAuth } from './AuthContext'

/**
 * 受保护路由守卫(对齐 risk-console App 的 Protected):
 * 未加载 → 骨架;未认证 → 跳登录并记 returnTo;缺 permission → 跳 /forbidden;否则渲染子路由。
 */
export function RequireAuth({ permission }: { permission?: string }) {
  const auth = useAuth()
  const location = useLocation()
  if (!auth.loaded) return <PageSkeleton />
  if (!auth.authenticated) {
    const returnTo = encodeURIComponent(location.pathname + location.search)
    return <Navigate replace to={`/login?returnTo=${returnTo}`} />
  }
  if (!auth.can(permission)) return <Navigate replace to="/forbidden" />
  return <Outlet />
}

/** 已登录访客不应停留登录页(对齐 risk-console 的 GuestOnly)。 */
export function RequireGuest() {
  const auth = useAuth()
  if (!auth.loaded) return <PageSkeleton />
  return auth.authenticated ? <Navigate replace to="/dashboard" /> : <Outlet />
}
