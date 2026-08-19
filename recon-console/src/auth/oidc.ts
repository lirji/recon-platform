import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { AUTH_CONFIG, AUTH_MODE } from './config'

// 对齐 risk-console/src/auth/oidc.ts:授权码 + PKCE(oidc-client-ts 默认 S256)、state、session storage、
// 回调 /auth/callback、登出回落 /login、无自动静默续期(401 时按需一次静默续期,见 api client)。

export interface LoginState {
  returnTo?: string
}

/** returnTo 同源相对路径白名单:必须以 / 开头、非 //、且不落回登录/回调页(防开放重定向 + 回跳环)。 */
export function sanitizeReturnTo(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return '/dashboard'
  if (value.startsWith('/auth/callback') || value.startsWith('/login')) return '/dashboard'
  return value
}

export const userManager = new UserManager({
  authority: AUTH_CONFIG.serverUrl,
  client_id: AUTH_CONFIG.clientId,
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: `${window.location.origin}/login`,
  response_type: 'code',
  scope: AUTH_CONFIG.scope,
  loadUserInfo: false,
  automaticSilentRenew: false,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
})

/** 取当前 OIDC 用户:未过期直接返回;过期尝试一次静默续期,失败清会话返回 null。 */
export async function currentOidcUser(): Promise<User | null> {
  if (AUTH_MODE !== 'oidc') return null
  const user = await userManager.getUser()
  if (!user) return null
  if (!user.expired) return user
  try {
    return await userManager.signinSilent()
  } catch {
    await userManager.removeUser()
    return null
  }
}

export async function getAccessToken(): Promise<string | undefined> {
  return (await currentOidcUser())?.access_token
}

/** 401 时的一次受控静默续期(拿新 token);失败清会话返回 undefined。 */
export async function renewAccessToken(): Promise<string | undefined> {
  if (AUTH_MODE !== 'oidc') return undefined
  const existing = await userManager.getUser()
  if (!existing) return undefined
  try {
    return (await userManager.signinSilent())?.access_token
  } catch {
    await userManager.removeUser()
    return undefined
  }
}
