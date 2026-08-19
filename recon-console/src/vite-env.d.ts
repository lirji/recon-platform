/// <reference types="vite/client" />

interface ImportMetaEnv {
  // 后端 API base(默认空=同源 /recon,dev 由 Vite 代理)
  readonly VITE_RECON_API_BASE?: string
  readonly VITE_RECON_API_TARGET?: string
  // A1 认证:dev(本地免 Casdoor) | oidc(Casdoor 授权码+PKCE)
  readonly VITE_AUTH_MODE?: 'dev' | 'oidc'
  readonly VITE_CASDOOR_SERVER_URL?: string
  readonly VITE_CASDOOR_CLIENT_ID?: string
  readonly VITE_CASDOOR_ORGANIZATION?: string
  readonly VITE_CASDOOR_APP_NAME?: string
  readonly VITE_CASDOOR_SCOPE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
