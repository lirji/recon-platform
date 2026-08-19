# A1 认证与鉴权 · 实施进度

> 配套 `FINAL_PLAN.md`(获批 2026-08-19)。口径:重跑=admin · 未认证走应用内 LoginPage · 登出=本地+Casdoor 全局登出 · Casdoor 参数构建期 VITE_。
>
> **已按 `/LLM/` 生态对齐**(用户 2026-08-19 要求参照其他项目):权威范式 = `auth-platform/docs/新项目接入指南.md` + **`risk-platform`**(recon 的直接模板:「对象归属型 · SPA PKCE + 强 JWT 边界」)。**B2 已解**:接入指南 C1 明确「前端授权码+PKCE」是标准姿势,纯前端 PKCE 成立,不需要后端 BFF。

## 已完成 ✅ — 后端轨(recon-batch,`./mvnw -pl recon-batch -am test` = 91 tests 全绿)

**范式:对齐 risk-platform `CasdoorSecurityConfig`/`DevSecurityConfig`——profile 分离**:
- `dev`/默认(本地/测试)= `DevSecurityConfig` permitAll,**免 Casdoor 本地可跑绿**;业务测试无需注入身份(回归原样)。
- `secure`(`SPRING_PROFILES_ACTIVE=secure`)= `CasdoorSecurityConfig` 强鉴权。

| 改动 | 文件 | 说明 |
|---|---|---|
| 依赖 | `recon-batch/pom.xml` | `spring-boot-starter-oauth2-resource-server` + test `spring-security-test` |
| 强 JWT 边界 | `config/CasdoorSecurityConfig.java`(新,`@Profile("secure")`) | `NimbusJwtDecoder.withJwkSetUri` + 默认(时间戳/issuer)+ 边界(**audience allowlist + owner=组织 + 非空 sub**);401/403 统一 JSON |
| 本地放行 | `config/DevSecurityConfig.java`(新,`@Profile("!secure")`) | permitAll |
| 授权模型 | `config/CasdoorAuthorityMapper.java`(新) | 抄 risk:`permissions`(能力串)+ `roles`/`groups`(→`ROLE_`)+ `scope`;principal=`sub` |
| 属性 | `config/ReconAuthProperties.java`(新) | `recon.auth.{issuer,jwk-set-uri,organization,audiences,operator-claim}` |
| 错误体 | `config/SecurityErrorWriter.java`(新) | 401/403 → `{error,message}` |
| operator | `web/DiscrepancyController.java` | secure 取自 JWT `preferred_username`(缺失回退 sub);dev 回退请求体(`ClearRequest.operator` 保留为兜底) |
| header | `application.yml` | `server.max-http-request-header-size: 64KB`(Casdoor token ~9KB,漏了合法 token 报 400)+ `recon.auth.*` |
| 测试 | `web/SecurityRouteMatrixTest.java`(新,secure + `@MockBean JwtDecoder`) | 401 匿名 / 403 权限不足 / 权限放行 / operator 取自 JWT;业务测试(dev)未改 |

**授权矩阵(permissions,Casdoor 是角色→权限唯一真相源)**:读 `/recon/**`=`recon.read`;`POST /recon/runs`·`/rerun`=`recon.launch`;`POST /recon/discrepancies/*/{resolve,close}`=`recon.dispose`。角色映射(Casdoor 侧 seed):viewer→{recon.read},operator→+{recon.dispose},admin→+{recon.launch}。

## 已完成 ✅ — 平台侧 Casdoor 供给(自建脚本 + 真跑 + 端到端冒烟)

- 新建 `auth-platform/deploy/recon-platform-provision.sh`(照 `risk-platform-provision.sh`,幂等;无机器身份/无 SpiceDB)。
- 已对本地 auth-platform Casdoor 跑通:org `recon-platform` + 用户 `recon-e2e-admin`(admin 角色,sub=`fe94b8bc-…`)+ 角色 viewer/operator/admin + 权限 `recon.read`/`recon.dispose`/`recon.launch`;password-grant token claim(owner/sub/aud/permissions)已校验。
- **secure profile 端到端冒烟通过**(fat jar + 真 Casdoor token):无 token→401、真 admin token→200、篡改→401、admin 发起缺 scenario→400(越过授权卡业务校验)。

**连接值(已产出,填 `secure` profile env)**:
```
SPRING_PROFILES_ACTIVE=secure
RECON_AUTH_ISSUER=http://localhost:8000
RECON_AUTH_JWK_SET_URI=http://localhost:8000/.well-known/jwks
RECON_AUTH_ORG=recon-platform
RECON_AUTH_AUDIENCES=ragshared0client00000001-org-recon-platform
```
前端 `VITE_*`:`VITE_CASDOOR_SERVER_URL=http://localhost:8000`、`VITE_CASDOOR_CLIENT_ID=ragshared0client00000001-org-recon-platform`、`VITE_CASDOOR_ORGANIZATION=recon-platform`、`VITE_CASDOOR_APP_NAME=rag-shared`。
本地测试账号:`recon-e2e-admin` / 口令 `Recon@Local-2026`(admin 角色;联调用,勿提交)。重跑供给改脚本 env 即可加 viewer/operator 测试用户。

## 已完成 ✅ — 前端轨(recon-console,镜像 `risk-console/src/auth/`,`pnpm build`+`test`+`e2e` 全绿)

用 **`oidc-client-ts`** + dev/oidc 双模(对应后端 dev/secure)。新增:
- `src/auth/{config,oidc,tenantSelection,AuthContext,RequireAuth}.ts(x)`:授权码+PKCE S256、session storage、回调 `/auth/callback`、returnTo 同源白名单、401 一次静默续期(client.ts)、组织确认 fail-closed、`can(permission)` 门控。
- `src/pages/{LoginPage,CallbackPage,ForbiddenPage}.tsx`;`src/components/layout/UserMenu.tsx`(头像+角色+登出走 Casdoor `end_session`)。
- 后端补 `web/AuthController.java` `GET /recon/auth/me`(权限权威来源,dev 返回全权限身份)。

改:`router.tsx`(AuthProvider 根 + /login + /auth/callback + /forbidden + RequireAuth 守 AppLayout,需 recon.read)、`api/client.ts`(Bearer + 401 续期,保留 ApiError/409)、`api/{recon,types}.ts`(getMe/UserSession)、`AppLayout`(UserMenu 替占位 Tag)、`DiscrepancyDetailDrawer`(删手填 operator + sessionStorage,核销/关闭按 recon.dispose 门控,operator 取自会话)、`Dashboard/RunsPage/RunDetailDrawer`(发起/重跑按 recon.launch 门控)、`.env.example`/`vite-env.d.ts`、`StrictMode once-guard`(CallbackPage)。

测试:`test/render.tsx` 注入 mock 会话(可覆盖 permissions 测门控);`DiscrepancyDetailDrawer.test` 改为 operator 取自身份 + 新增 viewer 隐藏门控用例;e2e 加 `/recon/auth/me` mock + 删手填 operator。**5 单测 + desktop/Pixel5 e2e 全过。**

## 已完成 ✅ — oidc 真实浏览器联调(Chrome 跑通全链路)

`VITE_AUTH_MODE=oidc` 前端(127.0.0.1:5173,代理→secure 后端 18080)+ 真 Casdoor,浏览器实测:
- 未认证 → 跳 `/login`(oidc 登录页);点登录 → Casdoor 授权请求参数全对(client_id `-org-recon-platform`、redirect_uri `/auth/callback`、`response_type=code`、**PKCE S256**、state)。
- Casdoor 登录 recon-e2e-admin → 回调 `/auth/callback?code&state` → **PKCE 跨域换 token 成功** → `/auth/me`(Bearer)→ secure 后端**真 JWT 验签通过** → 工作台。
- 头部显示 **recon-e2e-admin + 管理员**;admin 见**发起对账**(`recon.launch` 门控);登出 → Casdoor `end_session` → `/login`;重登**自动跳工作台**。

**联调中发现并修复 2 处**(评审 N1 预警的一处 + 一处 UX):
1. `CallbackPage` StrictMode + `active` 标志冲突致换 token 后不跳转 → 去掉 `active`,靠 once-guard(ref 跨双挂载保留)。
2. 展示/审计名回退链 `preferred_username → name(Casdoor 用户名) → sub`(`AuthController.displayName`,`DiscrepancyController` 复用)——否则显示 sub UUID。

修复后回归全绿:后端 `SecurityRouteMatrixTest`/`DiscrepancyControllerTest` 等 + 前端 `build`/`test`/`e2e`。

## A1 完成 ✅

后端(profile 分离 + permissions 矩阵 + 强 JWT 边界)+ 平台 Casdoor 供给 + 前端(oidc-client-ts)+ **真实浏览器端到端联调**全部完成并验证。生产切换:后端 `SPRING_PROFILES_ACTIVE=secure` + `RECON_AUTH_*`;前端 `VITE_AUTH_MODE=oidc` + `VITE_CASDOOR_*`。
