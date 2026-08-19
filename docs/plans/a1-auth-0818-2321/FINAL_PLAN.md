# A1 认证与鉴权 · 实施计划(FINAL_PLAN)

> 由 frontend-plan 工作流产出,配套 `DECISION_RECORD.md`。范围:recon-console 前端 + recon-batch 后端 OAuth2 Resource Server。**获你批准前不修改任何代码。**
>
> 前提(已定):Casdoor 自托管 IdP;OIDC 授权码 + PKCE(S256)纯前端换 token;无状态 JWT;完整 RBAC viewer/operator/admin。生成时间 2026-08-18。
>
> **⚠️ 生态对齐修正(2026-08-19,以 `PROGRESS.md` 为准)**:据用户要求参照 `/LLM/` 其他项目,已对齐 `auth-platform` 接入指南 + `risk-platform` 范式。**B2 已解**(前端 PKCE 是标准,无需 BFF)。落地相对本文若干处的修正:后端用 **profile 分离**(dev permitAll / secure Casdoor)而非「占位解码器」;授权用 **permissions 模型**(`recon.read/dispose/launch`)而非 ROLE_;前端用 **`oidc-client-ts`**(非 casdoor-js-sdk)、回调 **`/auth/callback`**、**session storage**、**401 先一次静默续期**、`/login` 确认组织,镜像 `risk-console/src/auth/`。本文其余结构仍有效。
>
> **本版已并入独立评审**(B1/B2 阻断、S1–S5 修正、N1–N5 改进)。**两个实施前置(必须先做)**:①**阶段 0.5 PKCE 公有客户端 spike**(B2,验证 Casdoor 能否无 client_secret 换 token,否则回退 BFF);②**后端测试地基**(B1,测试 profile mock `JwtDecoder` + `spring-security-test` + 13+ 用例改造,§6.4)。这两项不落地,分别会让「前端换 token 路径」和「`./mvnw test` 全绿」落空。

---

## 1. Goals / Non-goals

**Goals**
- 前端接入 Casdoor 登录(授权码 + PKCE),未登录访问受保护路由自动跳登录,回调换 JWT 后回原深链。
- axios 统一注入 `Authorization: Bearer`;401 全局跳登录、403 越权提示,不破坏现有 `ApiError`/409 语义。
- 头部展示当前用户 + 角色 + 登出;按角色门控四个写操作(launch/rerun/resolve/close)。
- **退役手填 operator**(`sessionStorage['recon-console.operator']`),operator 由**后端从 JWT 派生**落库。
- 后端 recon-batch 变为 OAuth2 Resource Server:按 JWKS 验签、角色 claim→Spring 权限、按接口授权矩阵、operator 取自可信身份。
- 单测 + Pixel 5 e2e 继续跑绿,新增鉴权/角色门控用例。

**Non-goals(本轮不做)**
- 注册 / 改密 / 找回 / 用户·角色·组织管理 —— 归 Casdoor。
- refresh_token 静默续期(`prompt=none`)—— 列为后续增强;本轮过期即重走授权码。
- 后端 BFF + HttpOnly Cookie 模式(与前端 JWT Bearer 架构冲突)。
- 运行期配置注入(`window.__ENV__`/config.json)—— 本轮构建期 `VITE_*`;后续再做。
- B3 自动冲正 / B5 工单审批(依赖 A1,后续里程碑)。

---

## 2. 视觉方向与设计参考(沿用既有视觉语言)

**沿用依据**(带路径):色板 `src/theme/colors.ts`(primary `#315EFB` / primarySoft `#EEF3FF` / success `#16A36A` / error `#D92D20` / 文本三级);AntD 主题 `src/theme/theme.ts`(圆角 8/12、controlHeight 36/40、headerBg 白、headerHeight 56);`src/styles/global.css` 的 `.brand`/`.brand-mark`(渐变方块 logo)、`.detail-hero`(`linear-gradient(135deg,#f7f9fc,#eef3ff)` 渐变面板)、`.page-eyebrow`、`focus-visible` 范式、`@991/@768` 媒体查询。

- **登录页**:复用 `.detail-hero` 渐变卡片语言 —— 居中 `Card`(max-width≈400、borderRadiusLG 12),顶部复用 AppLayout 的 `brand`(`.brand-mark` + 「对账运营台」),`.page-eyebrow` 风格副标题,主按钮全宽 `type=primary size=large`「使用 Casdoor 登录」。背景沿用 `body` 径向渐变。**不新造视觉**。
- **回调页**:纯状态页,居中 `Spin`/`PageSkeleton` +「正在完成登录…」;失败复用 `ErrorState`(Alert+重试)。
- **头部用户菜单**:`AppLayout.tsx:69` 占位 Tag → `Dropdown`(click)+ `Avatar`(用户名首字、primary 底)+ 用户名 + 角色 `Tag`(admin→geekblue/operator→green/viewer→default)+ 菜单项(邮箱只读、分隔、「退出登录」LogoutOutlined danger)。**首次引入 antd `Dropdown`/`Avatar`**。
- 文案沿用 `zhCN` 全中文语气。

> 视觉方向为「沿用」,非新方向,无需参考站选型。

---

## 3. 路由与页面流

**新增路由(在 `AppLayout` 之外的公共层)**:
```
[{ element:<AuthProvider/>, children:[
   { path:'login',    element:<LoginPage/> },          // AppLayout 之外,无侧栏
   { path:'callback', element:<CallbackPage/> },        // 授权码回调
   { element:<RequireAuth/>, children:[                 // 守卫层
      { path:'/', element:<AppLayout/>, children:[      // 现有受保护路由不变
         index→/dashboard, dashboard, runs, discrepancies, *→/dashboard ] } ] },
]}]
```
> `redirectPath` 采用 `/callback`(与移动端子代理建议的 `/auth/callback` 二选一,本计划取 `/callback`,更短;需与 Casdoor `redirect_uri` 一致)。`nginx.conf.template` 的 `try_files … /index.html` 已支持该深链刷新。

**用户流(默认:先展示应用内 LoginPage,口径 #3)**:未登录访问任意受保护路由 → `RequireAuth` 存 `returnTo` 后 `<Navigate to="/login">` → **LoginPage 点「使用 Casdoor 登录」** → `signin_redirect()`(S256 PKCE,verifier 存 sessionStorage)→ Casdoor 登录 → 回调 `/callback?code&state` → `exchangeForAccessToken()` 换 JWT(校验 state/verifier)→ `parseAccessToken` 解 identity+roles → `dispatch(loginSuccess)` + `history.replaceState` 剥离 URL 里的 code → 跳回 `returnTo` → 正常使用(axios 自动带 Bearer,写控件按角色显隐)→ 登出(清内存+sessionStorage token、`queryClient.clear()`、**Casdoor `end_session` 全局登出**(`post_logout_redirect_uri=<console>/login`)、回落 `/login`)。
> **口径 #3 备选**:若你选「直接跳 Casdoor」,则 `RequireAuth` 改为直接 `signin_redirect()`(不经 LoginPage)。§3/§5 现按**默认=有 LoginPage** 描述,二者对应不同 `RequireAuth` 实现,批准时二选一。

---

## 4. 组件树(复用现有 vs 新建)

```
main.tsx (改: router 内新增 AuthProvider 层, 见 §3)
└─ AuthProvider [新] src/auth/AuthContext.tsx  (useReducer, 注册 onUnauthorized)
   ├─ LoginPage [新] src/pages/LoginPage.tsx        复用 Card/brand/.detail-hero/PageHeader 风格
   ├─ CallbackPage [新] src/pages/CallbackPage.tsx   复用 PageSkeleton/ErrorState
   └─ RequireAuth [新] src/auth/RequireAuth.tsx
      └─ AppLayout [改] 头部 Tag→UserMenu[新] src/components/layout/UserMenu.tsx (Dropdown+Avatar)
         ├─ DashboardPage [改] 发起按钮 usePermission('launch') 门控
         ├─ RunsPage [改] 发起按钮门控
         │   └─ RunDetailDrawer [改] 重跑按钮 usePermission('rerun') 门控
         │   └─ LaunchRunModal (不改)
         └─ DiscrepanciesPage [改?] 无写操作, 大概率不改
             └─ DiscrepancyDetailDrawer [改] 核销/关闭门控 + 删手填 operator + 删 sessionStorage
```
**新建非组件模块**:`src/auth/casdoor.ts`(Sdk 实例)、`src/auth/token.ts`(模块级 get/set,桥接 axios)、`src/auth/useAuth.ts`、`src/auth/usePermission.ts`(动作→角色矩阵)。

**复用**:`AsyncState`(PageSkeleton/ErrorState/EmptyState)、`PageHeader`、`StatusTag`、`utils/format.ts:errorMessage`、`App.useApp().message`、`theme/colors.ts`、`global.css` 各范式。

---

## 5. 状态与边界情况(逐页)

| 页面/区域 | loading | empty | error | 特殊边界 |
|---|---|---|---|---|
| LoginPage | 按钮 loading「正在跳转登录…」禁重复点 | — | 回调回跳带错误/参数缺失/state 不匹配 → Alert +「重新登录」 | 已登录访问 /login → 直接跳 returnTo 或 /dashboard |
| CallbackPage | Spin/PageSkeleton「正在完成登录…」 | — | code 交换失败 / state 校验失败 → ErrorState +「返回登录」 | **StrictMode 双挂载 → 只换一次 code**(useRef/module once-guard);换成功即清 code |
| RequireAuth | 无(瞬时判定) | — | — | 未认证→存 returnTo 后跳登录;returnTo **仅同源相对路径白名单**(`/` 开头、非 `//`) |
| 头部 UserMenu | — | — | — | 角色缺失→按 viewer 兜底;移动端收成纯头像 |
| 写控件门控 | — | — | 403→`message.error('无权执行该操作')`,不跳登录 | viewer:launch/rerun 隐藏;resolve/close 桌面禁用+Tooltip、移动隐藏 |
| 全局会话 | — | — | 401→`message.warning('登录状态已过期')`+跳登录(单次,防循环) | 401 豁免**按请求 URL**(仅 `/recon/**` 触发跳登录),非全局开关;**多标签不做跨标签同步**(D5 选 sessionStorage=标签隔离,`storage` 事件对 sessionStorage 不跨标签触发,与现 operator 习惯一致;如未来必须,用 `BroadcastChannel` 显式广播,非本轮) |

---

## 6. API 契约

### 6.1 前端 → 后端(recon-batch,同源 `/recon`,带 Bearer)
- 所有现有 `/recon/**` 请求头新增 `Authorization: Bearer <jwt>`。
- **写接口 DTO 变更**:`resolve`/`close` 的 `ClearRequest` 中 `operator` **前端不再传**(后端从 JWT 取,忽略请求体值);前端 Modal 删除操作人输入。`note`/`expectedVersion` 保留。launch/rerun 请求体不变(本就不带 operator)。

### 6.2 前端 → Casdoor(跨域,`serverUrl`)
- `signin_redirect()` → `GET /login/oauth/authorize?client_id&redirect_uri&response_type=code&scope=openid profile email&state&code_challenge&code_challenge_method=S256`。
- `exchangeForAccessToken()` → Casdoor token 端点换 JWT(PKCE verifier)。
- **前置(装配期依赖,非本仓代码)**:Casdoor 建应用、配 `redirect_uri=<console>/callback`、开启 CORS、配 RBAC 角色(viewer/operator/admin)、token 内含角色 claim。

### 6.3 后端 recon-batch OAuth2 Resource Server 契约(本计划一并交付)
- 依赖:`spring-boot-starter-oauth2-resource-server`(传递引入 spring-security)+ **测试**依赖 `spring-security-test`。
- `application.yml`:`spring.security.oauth2.resourceserver.jwt.issuer-uri: ${CASDOOR_ISSUER}`(OIDC discovery 自动拿 JWKS `/.well-known/jwks`)。**⚠️ 测试期陷阱(见 §6.4)**:`issuer-uri` 会在 Bean 创建时同步拉取 `<issuer>/.well-known/openid-configuration`,CI 无 Casdoor 会导致**所有 `@SpringBootTest` 上下文加载失败**——测试 profile 必须用 mock `JwtDecoder` 覆盖。
- `SecurityFilterChain`(recon-batch `config` 包):`sessionCreationPolicy=STATELESS`、`csrf.disable()`(Bearer 无 Cookie)、`oauth2ResourceServer().jwt(jwtAuthenticationConverter)`。**CORS**:生产 console 与 `/recon` 同源(见 `nginx.conf.template`;dev 由 Vite 代理),后端**无需**为 console 开 CORS,加了属死配置且可能掩盖代理错配;仅当某环境前后端**分域**时才显式放行那个具名域。**ArchUnit**:仓库**无**「Spring 只在 batch」规则——`recon-batch/ArchitectureTest` 只约束 JDBC(限 `persistence`/`config`)与 Spring **Batch**(限 `job`/`config`),Spring **Security 不受约束**,`DiscrepancyController` 本就在 `batch.web` 用 Spring MVC;故 `SecurityConfig` 放 `config`、`@AuthenticationPrincipal Jwt`/`@PreAuthorize` 进 `web` 都不触发门禁。复核目标仅为「未向 `persistence`/`domain` 引入新的 JDBC/Batch 依赖」。
- **角色映射**:`JwtAuthenticationConverter` 从角色 claim 提取 → `ROLE_*` 权限。Casdoor 默认 JWT 内嵌 user 对象(`roles` 为对象数组),**推荐配置 Casdoor JWT-Custom** 用 Token attributes 把 `Roles` 映射成扁平字符串数组 claim(如 `roles:["admin"]`),转换器直接读;并提供兜底转换器处理嵌套结构。
- **授权矩阵**:

| 方法/路径 | 需要 | 说明 |
|---|---|---|
| `GET /recon/**`(dashboard/runs/discrepancies 读) | 已认证(viewer+) | 只读 |
| `POST /recon/runs`(发起) | `admin` | launch |
| `POST /recon/runs/{id}/rerun`(重跑) | `admin`(待确认,见 C1) | rerun |
| `POST /recon/discrepancies/{id}/resolve` | `operator`/`admin` | 核销 |
| `POST /recon/discrepancies/{id}/close` | `operator`/`admin` | 关闭 |
| `/actuator/health/**`(A4 后) | permitAll | 探活 |

- **operator 取值(前后端锁同一 claim)**:统一用 **`preferred_username`**(展示与审计同源,避免 N4 不一致)。`DiscrepancyController` 的 resolve/close 从 `@AuthenticationPrincipal Jwt` 取 `preferred_username` 作 operator 落库,**不读 `ClearRequest.operator`**;`ClearRequest.operator` 移除(保留 note/expectedVersion)。JWT 派生值天然非空且有界,**原「operator 空/超长→400」的校验作废**(见 §6.4 受影响用例)。launch/rerun 本轮**仅授权、不落发起人审计**(加字段属附加范围,列 §12 已知缺口)。
- **触及**:`DiscrepancyController.java`(4 端点授权注解 + operator 取自 JWT + 注释更新)、`ClearRequest`(移除 operator)、`ManualClearingService`(operator 校验去留)、新增 `SecurityConfig`/`JwtRoleConverter`、`pom.xml`(batch,含 test scope `spring-security-test`)、`application.yml`(issuer-uri + 测试 profile mock JwtDecoder)。

### 6.4 后端测试影响(B1 — 计划原估严重不足,必须先解决)

加 `SecurityFilterChain` 会打红 recon-batch 全部 **8 个 `@SpringBootTest`**(`DiscrepancyControllerTest`、`ReconConsoleControllerTest`、`AbstractReconJobIT`、`AbstractThreeWayJobIT`、`PersistenceStoreIntegrationTest`、`ReconRunSeqConcurrencyTest`、`AlertRelayServiceTest`、`ManualClearingServiceTest`),两条硬伤:

1. **上下文加载即失败**:`issuer-uri` 触发 `JwtDecoders.fromIssuerLocation()` 在 Bean 创建时同步拉 discovery 端点,CI 无 Casdoor → 8 个全 context load 失败(不止 controller)。**对策**:测试 profile 用 `@TestConfiguration` 提供 mock `JwtDecoder`(或 `jwk-set-uri` 指向本地 stub),不走真实 issuer。放进 `AbstractReconJobIT`/共享测试基类,一处覆盖。
2. **未认证 MockMvc 全变 401**:`DiscrepancyControllerTest`(约 11 方法)+ `ReconConsoleControllerTest` 读接口用例不带 token,加过滤链后一律 401。**对策**:引入 `spring-security-test`,给用例挂 `.with(jwt().authorities(...))`;按角色矩阵补越权 403 用例。
3. **operator 校验类用例改写(S3)**:`DiscrepancyControllerTest.blankOperatorReturns400`、`manualClearingRejectsValuesThatExceedStorageContract`(operator 65 字符→400)断言的是**请求体 operator** 校验;operator 改取 JWT、`ClearRequest.operator` 移除后,这两条 400 语义消失,需删除/改写为「operator 来自 JWT、落库值=`preferred_username`」;`ManualClearingService` 内 operator 长度/空值校验相应移除或改为对 JWT 派生值的断言。

> **受影响后端用例清单(13+):** DiscrepancyControllerTest 全部方法(带 token)+ 其中 2 条 operator 校验用例重写 + ReconConsoleControllerTest 读用例带 token + 6 个 IT/并发/服务测试补 mock JwtDecoder。这是「§11.8 `./mvnw -q test` 全绿」的真实工作量,不可低估。

---

## 7. 响应式与移动端适配策略

**断点表(沿用)**:`screens.md`(≥768)控表格↔卡片/Drawer 宽/门控隐藏;`screens.lg`(≥992,`isMobile=!lg`)控 Sider↔Drawer/头部菜单形态;CSS 只在 `@991/@768` 追加。

**逐页小屏**:
- 登录页:`min-height:100dvh` flex 居中(超矮视口回退 `flex-start` + 可滚,避键盘顶飞),卡片 `width:100%;max-width:400px` 留 16px gutter,`Form layout=vertical`,提交按钮 `block`+`min-height:44px`。
- 回调页:居中 Spin,天然自适应。
- 头部菜单:`lg` 头像+名+角色 Tag;`!lg` 纯头像 Dropdown(复用 `@991` 对文本 `display:none` 手法),名/角色/登出进面板;与既有 hamburger 共存。
- 门控:移动端(`!screens.md`)对无权角色**隐藏**写控件(避免一排全宽灰按钮);桌面 resolve/close 禁用+Tooltip(禁用 Button 外包 `<span>`,`trigger=['hover','focus']`)。
- `viewport-fit=cover`/safe-area:本轮维持现状(无 PWA 诉求),列为可选。

**移动端验收**:见 §9。

---

## 8. 文件级改动清单

**前端 · 新增**
- `src/auth/casdoor.ts`、`src/auth/token.ts`、`src/auth/AuthContext.tsx`、`src/auth/useAuth.ts`、`src/auth/usePermission.ts`、`src/auth/RequireAuth.tsx`
- `src/pages/LoginPage.tsx`、`src/pages/CallbackPage.tsx`
- `src/components/layout/UserMenu.tsx`
- `.env.example` 增 `VITE_CASDOOR_SERVER_URL/_CLIENT_ID/_APP_NAME/_ORG_NAME/_REDIRECT_PATH/_SCOPE`、`VITE_AUTH_ENABLED`;`src/vite-env.d.ts` 补 `ImportMetaEnv` 类型

**前端 · 修改**
- `src/router.tsx`(新增 AuthProvider/login/callback/RequireAuth 层)
- `src/api/client.ts`(请求拦截器注 Bearer + 401 注册回调,保留 ApiError)
- `src/main.tsx`(如需在 provider 链登记 message 回调)
- `src/components/layout/AppLayout.tsx`(Tag→UserMenu)
- `src/components/discrepancies/DiscrepancyDetailDrawer.tsx`(删手填 operator + sessionStorage,核销/关闭门控)
- `src/pages/RunsPage.tsx`、`src/pages/DashboardPage.tsx`(发起按钮门控)、`src/components/runs/RunDetailDrawer.tsx`(重跑门控)
- `src/test/render.tsx`(renderApp 默认注入 operator 身份的 AuthProvider)
- `src/components/discrepancies/DiscrepancyDetailDrawer.test.tsx`(删手填 operator 断言,改断言 operator 来自 JWT)
- `e2e/console.smoke.spec.ts`(addInitScript 注入伪 JWT + mock Casdoor;删手填 operator)
- `vite.config.ts`(可选:manualChunks 归 casdoor-js-sdk 入 `auth` chunk)
- `package.json`(`pnpm add casdoor-js-sdk`)

**后端 · 修改/新增**(recon-batch)
- 新增 `.../config/SecurityConfig.java`、`.../config/JwtRoleConverter.java`
- `pom.xml`(oauth2-resource-server)、`application.yml`(issuer-uri、CORS)
- `.../web/DiscrepancyController.java`(operator 取自 JWT,授权注解)、`ClearRequest`(operator 语义)
- 复核 `ArchitectureTest`(security 落在 batch 允许包)

---

## 9. 按依赖排序的实施步骤

> 前端与后端可并行,但**后端授权矩阵是前端联调前置**。建议后端先出可验签的骨架。

**阶段 0 · 装配前置(非代码)**:Casdoor 建应用、配 redirect_uri/CORS/角色/token claim;记 issuer/clientId/appName/org 到各环境 env。
**阶段 0.5 · PKCE 公有客户端 spike(B2 — 地基验证,~15 分钟,前端换 token 路径的前置)**:用**目标 Casdoor 版本**把应用配成 public client,手工跑通「授权码 + PKCE(S256)**不带 client_secret** 换 JWT」,并确认 `casdoor-js-sdk` 的 `signin_redirect`/`exchangeForAccessToken`/`parseAccessToken` 只发 `code_verifier`、方法签名与本计划一致。**若该 Casdoor 版本强制要 client_secret**,则纯前端 PKCE(D2)不成立 → 回退到后端 BFF 代换(`/api/signin`,原被 Non-goals 排除),前端 `exchangeForAccessToken` 路径需改走后端;**此结论必须在写前端换 token 代码前拿到**。

**后端轨**
1. 加 oauth2-resource-server 依赖 + test scope `spring-security-test`;`application.yml` issuer-uri;`SecurityConfig`(STATELESS/csrf off)+ `JwtRoleConverter`;先放行全部只做验签,跑通「带 Bearer 能过、无 token 401」。
2. **先落测试地基(B1,阻断)**:测试 profile `@TestConfiguration` mock `JwtDecoder`(共享基类,救回 8 个 `@SpringBootTest` 的 context 加载);给现有 MockMvc 用例挂 `.with(jwt())`。**这步不做,后续一切后端测试全红。**
3. 上授权矩阵(GET 认证、写接口 hasRole);`DiscrepancyController` operator 改取 JWT `preferred_username`,移除 `ClearRequest.operator`;更新注释。
4. 改写 operator 校验类用例(§6.4.3:删/改 `blankOperatorReturns400`、operator 超长用例;调整 `ManualClearingService` 校验);补越权 403、operator 落库=JWT 用例;复核 ArchUnit(仅确认无新 JDBC/Batch 依赖入 persistence/domain)。

**前端轨**
4. `pnpm add casdoor-js-sdk`;建 `src/auth/{casdoor,token}.ts` + env + `vite-env.d.ts` 类型。**不变量(N3)**:`token.ts` 在应用启动时**同步**从 sessionStorage 水合 `getToken()`;受保护查询只在 `RequireAuth` 通过后挂载,故首个 `/recon` 请求必带 token——实施勿用异步初始化打破它。
5. `AuthContext`(useReducer)+ `useAuth` + `usePermission`(动作矩阵)+ `RequireAuth`。
6. `router.tsx` 接 AuthProvider/login/callback/RequireAuth;`LoginPage`/`CallbackPage`(含 StrictMode once-guard + state/verifier 校验 + replaceState 清 code + returnTo 白名单)。
7. `client.ts` 请求拦截器注 Bearer + 401 注册回调;react-query 401 关重试。
8. `AppLayout` 头部 `UserMenu`;四个写控件接 `usePermission` 门控(隐藏/禁用按 D6)。
9. `DiscrepancyDetailDrawer` 删手填 operator + sessionStorage,resolve/close 门控。
10. 测试:`render.tsx` 注入身份;改 `DiscrepancyDetailDrawer.test.tsx`;新增 auth/守卫/门控单测;e2e 注入伪 JWT + Pixel 5 断言。

**联调**:11. 前后端 + Casdoor 端到端(登录→带 Bearer→角色门控→越权 403→登出)。

---

## 10. 测试策略

**单测(Vitest/jsdom,注意 `matchMedia` 恒 false=小屏)**:
- auth store/hook(token 解码、角色派生、`exp` 过期)、`RequireAuth`(未认证→`<Navigate to="/login">`+存 returnTo)、`usePermission` 矩阵(viewer/operator/admin × launch/rerun/resolve/close)、`client` 请求拦截器注 Bearer + 401 单次触发(仅 `/recon/**`)、`CallbackPage`(**StrictMode once-guard 按 `code` 值做键**——非布尔,避免二次登录被永久短路;state 校验、replaceState 清 code、returnTo 同源白名单)。
- mock:优先 mock 自有 `src/auth/*`(如现有 mock `api/recon`);仅回调换 token/解码用例直连 mock `casdoor-js-sdk`。断点相关用例内 override `matchMedia`(md=true 对比桌面)。
- **`render.tsx` 默认注入 operator 身份**,使 `RunsPage.test.tsx`/`DiscrepancyDetailDrawer.test.tsx` 看得到写按钮而零大改;后者删手填 operator、改断言 operator 来自 JWT。

**e2e(Playwright,desktop-chromium + Pixel 5)**:
- E1 seeded operator JWT → 现有 dashboard→runs→discrepancies→核销 冒烟绿(两 project)。
- E2 viewer 登录:发起/重跑/核销 不可用;只读正常。
- E3 深链已登录直达 + 菜单高亮(移动端验抽屉菜单)。
- E4 未认证 goto → 跳登录 stub → 回 returnTo。
- E5(可选)401 触发单次登出、不循环。
- 登录态注入:`addInitScript` 预置伪 token(到 sessionStorage,键与 `token.ts` 一致)+ `page.route` mock Casdoor userinfo/token,保留真实守卫路径(不用 bypass 开关,除非 CI 要求)。
- **伪 JWT claim 形态(N2,三处必须对齐)**:与 Casdoor 默认一致用嵌套 `roles:[{name,owner,...}]`(或选 JWT-Custom 扁平 `roles:["operator"]`),`exp` 取未来、含 `preferred_username`/`name`。**`parseAccessToken` 解析、前端 `usePermission` 读取、后端 `JwtRoleConverter` 必须解同一形态**——e2e/单测的伪 JWT 与后端转换器同源定义。
- **N1 注意**:StrictMode 双挂载只在 dev;e2e 走 `pnpm preview`(生产构建)覆盖不到 once-guard,该项由单测(jsdom)验证,§11.验收勿指望 e2e 证明它。

---

## 11. 验收标准

1. 未登录访问 `/dashboard` 等 → 跳登录;登录后回原深链。
2. 登录后所有 `/recon/**` 自动带 `Authorization: Bearer`;后端无 token 返 401、越权返 403。
3. 角色门控:admin 见发起/重跑/核销/关闭;operator 见核销/关闭、不见发起/重跑;viewer 只读(写控件按 D6 隐藏/禁用)。
4. **operator 落库来自 JWT**(非前端手填);`DiscrepancyDetailDrawer` 无操作人输入框、无 `sessionStorage['recon-console.operator']`。
5. 401 全局单次跳登录不循环;403 提示不跳登录;现有 409 乐观锁语义不变。
6. 头部显示用户名+角色+登出;登出清会话跳登录。
7. **移动端验收(Pixel 5)**:登录卡片居中、按钮全宽 44px;头部收成头像 Dropdown 且与 hamburger 共存;viewer 下写控件不可见。
8. `pnpm test`、`pnpm build`、`pnpm e2e` 全绿;后端 `./mvnw -q test`(含 ArchUnit)全绿。

---

## 12. 风险与回滚

| 风险 | 缓解 |
|---|---|
| **B2 · Casdoor 可能强制 client_secret**(纯前端 PKCE 不成立→BFF 推倒重来) | 阶段 0.5 spike 先验证公有客户端换 token;不通过则回退后端 BFF(前端换 token 路径改走后端) |
| **B1 · Resource Server 打红全部 8 个 `@SpringBootTest`** | 先落测试 profile mock `JwtDecoder` + `spring-security-test` + 13+ 用例改造(§6.4),再上授权矩阵 |
| **StrictMode 双换 code**(一次性 code 第二次 400) | once-guard **按 code 值做键**;换成功即清 code、吞「已消费」错误(e2e 覆盖不到,靠单测) |
| state/PKCE 校验缺位(CSRF/伪造回流) | 强制校验 state + code_verifier,不匹配拒绝回登录 |
| 401 重定向循环 / 刷新风暴 | auth 端点豁免 401 拦截;「已在登录页」短路 + 单次;并发 401 共享单登出 |
| 开放重定向(returnTo/回调注入外链) | 白名单:`/` 开头、非 `//`、同源 |
| XSS 取 token | token 内存为主、sessionStorage 只兜刷新、不落 refresh_token、短 TTL;禁 localStorage |
| 回调 URL 泄漏 code | 换 token 后 `history.replaceState` 清 query |
| e2e 被守卫外跳全红 | addInitScript 注入伪 JWT + mock Casdoor(联调前必先落地) |
| operator claim 与历史值形态不一致 | 仅影响观感;历史 operator 读服务端字段照常展示 |
| 构建期 env 烘焙,回滚需重建 | `VITE_AUTH_ENABLED` 构建开关做渐进上线;运行期注入列为后续 |
| launch/rerun 未落发起人审计 | 本轮仅授权;如需落初始人,后端加字段(附加范围),否则记为已知缺口 |
| Casdoor 角色 claim 形态未定 | JWT-Custom 扁平化 `roles` + 兜底嵌套转换器;装配期对齐 |

**回滚**:`VITE_AUTH_ENABLED=false` 重构建前端可临时短路守卫;后端 SecurityConfig 可通过 profile 关闭(仅限受控内网回退)。前端改动集中在 `src/auth/*` + 少量接线点,回退面可控。

---

## 13. 口径决议(2026-08-19 用户拍板,计划获批)

1. **重跑角色 = admin**(与 launch 同级)。授权矩阵 §6.3:`POST /recon/runs/{id}/rerun` 需 `admin`。
2. **登出 = 本地 + Casdoor 全局登出(end_session)**。登出流:清内存+sessionStorage token → `queryClient.clear()` → 调 Casdoor `end_session`(带 `post_logout_redirect_uri=<console>/login`)→ 回落 `/login`。前端 `UserMenu` 登出项走此路径。
3. **未认证 = 先展示应用内 LoginPage**。`RequireAuth` 未认证 → 存 returnTo → `<Navigate to="/login">`;LoginPage 点按钮再 `signin_redirect()`(采纳 §3 默认写法,非直跳 Casdoor 备选)。
4. **Casdoor 参数 = 构建期 `VITE_*` + `VITE_AUTH_ENABLED` 开关**(默认);运行期注入列为后续加固。
