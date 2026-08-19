// A1 认证配置。对齐 risk-console/src/auth/config.ts 的 dev/oidc 双模(对应后端 dev/secure profile)。
export type AuthMode = 'dev' | 'oidc'

// 默认 dev:本地免 Casdoor(后端 DevSecurityConfig permitAll,/recon/auth/me 返回全权限开发身份)。
// 生产/联调置 VITE_AUTH_MODE=oidc,走 Casdoor 授权码 + PKCE。
export const AUTH_MODE: AuthMode = import.meta.env.VITE_AUTH_MODE === 'oidc' ? 'oidc' : 'dev'

export const AUTH_CONFIG = {
  // Casdoor 统一登录服务(= OIDC issuer / authority)。
  serverUrl: import.meta.env.VITE_CASDOOR_SERVER_URL ?? 'http://localhost:8000',
  clientId: import.meta.env.VITE_CASDOOR_CLIENT_ID ?? 'ragshared0client00000001-org-recon-platform',
  organization: import.meta.env.VITE_CASDOOR_ORGANIZATION ?? 'recon-platform',
  scope: import.meta.env.VITE_CASDOOR_SCOPE ?? 'openid profile offline_access',
}
