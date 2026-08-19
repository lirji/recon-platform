# A1 认证与鉴权 · 决策记录(DECISION_RECORD)

> 由 frontend-plan 工作流综合 5 个只读子代理(需求与用户流 / UIUX 与交互状态 / 前端架构与复用 / 移动端适配 / 测试风险与边界)的调查产出。记录关键备选、权衡、裁决与理由;冲突处显式裁决。配套实施计划见同目录 `FINAL_PLAN.md`。
>
> 生成时间:2026-08-18。范围:recon-console 前端 + recon-batch 后端 OAuth2 Resource Server 契约。已定前提:Casdoor 自托管 IdP、OIDC 授权码 + PKCE(S256)、无状态 JWT、完整 RBAC(viewer/operator/admin)。

---

## 全体子代理一致确认的事实(无冲突)

- 后端**零 Spring Security**,前端**零鉴权**——端到端 greenfield 接入。
- Provider 链 `StrictMode → ConfigProvider(zhCN) → AntApp → QueryClientProvider → RouterProvider`;**`RouterProvider` 不接受 children** —— 这是最关键结构约束,决定了鉴权 Provider 必须作为**路由树内元素**挂载才能用 `useNavigate/useLocation`。
- `src/api/client.ts` 仅响应拦截器,归一化为 `ApiError(message,status,code)`;**`ApiError.status` 被业务依赖**(`DiscrepancyDetailDrawer.tsx:65` 用 `status===409` 做乐观锁冲突)——401 处理**不得破坏** status 可读性。
- 四个写操作触发点:`RunsPage.tsx:70`/`DashboardPage.tsx:57` 发起、`RunDetailDrawer.tsx:85` 重跑、`DiscrepancyDetailDrawer.tsx:92-93` 核销/关闭。
- `operator` 现由 `DiscrepancyDetailDrawer.tsx:53/75/203-205` 手填 + `sessionStorage['recon-console.operator']`;**全仓唯一读写点**,退役不影响历史 operator 的服务端展示。
- 项目**不是纯桌面系统**:有移动卡片视图、Pixel 5 e2e project、`global.css` 的 `@991/@768` 双断点、AppLayout 左侧 Drawer。移动端是一等公民,A1 必须覆盖。
- `src/test/setup.ts` 把 `matchMedia` 恒 mock 成 `false` → **单测默认走小屏分支**;`DiscrepancyDetailDrawer.test.tsx` 两条用例**强依赖手填 operator 输入框**,A1 后必破;e2e `goto('/dashboard')` 会被守卫外跳。

---

## 决策与裁决

### D1 · 鉴权状态放哪层 —— 裁定:Context + useReducer,AuthProvider 作路由根元素(备选 A)

| 备选 | 方式 | 优点 | 代价 |
|---|---|---|---|
| **A(采纳)** | `src/auth/AuthContext.tsx` 用 `useReducer` 管 `{status,token,user,roles}`,`AuthProvider` 渲染 `<Outlet/>`,在 `router.tsx` 作最外层路由 element | **零新依赖**、契合仓库极简风;Provider 在路由内 → 可用 router hooks 做守卫跳转;login/callback 天然置于 AppLayout 之外 | 需 `src/auth/token.ts` 模块级 `getToken/setToken` 桥接 axios(axios 是 React 树外单例) |
| B | 引入 zustand store | store 在树外,axios/组件统一读;免 Provider 放置取舍 | **引入新依赖 + 新抽象**;仓库当前状态一律 `useState`+react-query,与「不引入新抽象」硬规则相悖;收益本场景用不上 |
| C(否决) | react-query `useQuery(['auth/me'])` 持会话 | — | 把会话与服务端数据缓存混为一谈,登出失效/注入时序别扭 |

**理由**:auth 状态小且集中,Context+useReducer 足够;硬规则「优先复用既有栈、不引入新抽象」直接排除 B。仅当后续需在 React 树外大量读会话或出现重渲瓶颈才升级 zustand。

### D2 · Token 换取方式 —— 裁定:纯前端 PKCE(S256)公有客户端,无后端代换

- casdoor-js-sdk:`new Sdk({serverUrl,clientId,appName,organizationName,redirectPath})`;`signin_redirect()`(存 code_verifier)→ 回调 `exchangeForAccessToken()` 直连 Casdoor token 端点换 JWT → `parseAccessToken(token)` 解 claim。
- **理由**:契合「前端拿 JWT、后端只验签」的既定架构,无需后端 `/api/signin` 代换端点。**前提**:Casdoor 应用侧开启 CORS、配好 `redirect_uri=<console>/callback`(装配期依赖,见 FINAL_PLAN 前置)。

### D3 · axios 注入与 401 —— 裁定:新增请求拦截器注 Bearer;401 走注册式回调,不动现有 ApiError 链

- 请求拦截器:`Authorization: 'Bearer '+getToken()`(token 空则不加,保护 login/callback/探活)。
- 401:保留现有响应拦截器继续产 `ApiError`(**不能动**,409 依赖 status);auth 层启动时把 `onUnauthorized` 注册进 client,拦截器内 `if status===401 → onUnauthorized()` 后仍 `reject(ApiError)`。避免 axios 反向 import React。
- 配套:react-query 对 401 关重试(`retry:(n,err)=>!(err instanceof ApiError && err.status===401)`),避免默认 `retry:1` 无谓重打。

### D4 · 路由守卫写法 —— 裁定:包裹式 `RequireAuth` 组件(非 loader)

- `RequireAuth`:`status!=='authenticated'` → 存 `returnTo` 后 `signin_redirect()`(或渲染 LoginPage);否则 `<Outlet/>`。**理由**:与 Context 单一数据源一致、与既有懒加载/Suspense 心智一致、易测;loader 守卫需靠 `token.ts` 访问器,与 Context 会话态易出现双源。

### D5 · JWT 存储 —— 裁定:内存为主 + sessionStorage 兜刷新,禁用 localStorage

| 介质 | 抗 XSS 持久窃取 | 刷新存活 | 跨标签 |
|---|---|---|---|
| 纯内存 | 最强 | ✗(需静默重认证) | ✗ |
| **内存+sessionStorage(采纳)** | 较强(标签级、关页即清) | ✓ | ✗(标签独立,与现 operator 习惯一致) |
| localStorage | 最弱 | ✓ | ✓ |

- **理由**:运行时 token 在内存/Context,仅为刷新存活镜像一份到 sessionStorage(生命周期短于 localStorage、不跨标签);**不落 refresh_token**、短 TTL;续期走 Casdoor 会话静默(`prompt=none`,列为后续增强)。安全基线更高时可退化为纯内存 + 刷新一跳 Casdoor。**避免 localStorage**。
- 记录:更高安全方案是后端 BFF + HttpOnly Cookie,但与「前端 JWT Bearer」既定架构冲突,本轮不采。

### D6 · 角色门控:隐藏 vs 禁用 —— 裁定:高风险写隐藏;抽屉内 resolve/close 桌面禁用+Tooltip、移动隐藏

- 发起对账(Dashboard/Runs header)、重跑(RunDetailDrawer):无权角色**隐藏**(全视口)。
- 核销/关闭(DiscrepancyDetailDrawer):**桌面** viewer 时**禁用 + Tooltip**「需要 operator 及以上权限」(利于可发现性/培训);**移动端隐藏**(触屏无 hover、全仓零 Tooltip 先例,禁用态解释落地困难)。
- 门控统一由 `usePermission(action)` 布尔提供,避免各组件散落角色字符串比较。
- **理由**:综合 UIUX(隐藏 vs 禁用分场景)与移动端(触屏隐藏)两份,裁为「按视口 + 按控件」的组合策略。

### D7 · 移动端适配 —— 裁定:沿用既有方案,不另起炉灶

- 断点只用 `Grid.useBreakpoint()` 的 `screens.md`(≥768,表格↔卡片、Drawer 宽)与 `screens.lg`(≥992,Sider↔Drawer);CSS 只在 `global.css` 既有 `@991/@768` 两块追加。
- 登录/回调页置于 AppLayout 之外,`min-height:100dvh` 居中卡片(对既有 `100vh` 的小幅增量,规避虚拟键盘顶飞);主按钮全宽 `min-height:44px`(复用 `@768` 规则)。
- 头部用户菜单:`lg` 显示头像+名+角色 Tag;`!lg` 收成纯头像 Dropdown。
- **理由**:既有适配方案成熟且一致,硬规则要求优先沿用。

---

## 冲突点与显式裁决

| # | 冲突 | 来源 | 裁决 | 依据 |
|---|---|---|---|---|
| C1 | **重跑(rerun)归 admin 还是 operator** | 需求 agent→admin;架构 agent 矩阵→operator;UIUX→标为待澄清 | **归 admin**(与 launch 同级);**列为需你确认的产品口径项** | `docs/PHASE2_ROADMAP.md` 明确「admin 发起 Run」,rerun 是重新发起,归 admin 最一致;但属产品策略,批准时确认 |
| C2 | **状态管理 Context vs zustand** | 架构 agent 给 A/B | **Context(A)** | 硬规则「不引入新抽象」,见 D1 |
| C3 | **门控隐藏 vs 禁用** | UIUX(分场景)vs 移动端(隐藏) | **按视口+按控件组合**,见 D6 | 两份各自合理,按场景合并 |
| C4 | **token 交换在前端 vs 后端 BFF** | 架构 agent 假设纯前端 PKCE;测试 agent 提 BFF 为更安全备选 | **纯前端 PKCE** | 契合既定「前端拿 JWT」架构,见 D2;BFF 记录为更高安全备选不采 |
| C5 | **operator claim 取哪个字段** | 各 agent 均标待澄清(sub/name/preferred_username/displayName) | **前后端锁同一字段 `preferred_username`**(避免展示与审计不一致);**后端**从 JWT 取该值落库(权威侧),前端 resolve/close **不再传 operator**(`ClearRequest.operator` 移除) | 需与 Casdoor token 配置对齐,见 FINAL_PLAN §6.3;列为装配期依赖 |

---

## 移动端适配方案选型

仓库**已有**成熟适配方案(antd Grid 双断点 + `global.css` `@991/@768` + 移动卡片 + Pixel 5 e2e),按硬规则**直接沿用**,不做方案选型对比。A1 新增页面/控件全部落在既有断点与既有触控约定(44px)上。详见 FINAL_PLAN「响应式与移动端适配策略」。

---

## 产品/策略口径(2026-08-19 用户拍板,计划获批)

1. **重跑角色 = admin**(同 launch)。(C1 定案)
2. **登出语义 = 本地 + Casdoor 全局登出**(`end_session` + `post_logout_redirect_uri=<console>/login`)。
3. **未认证 = 先展示应用内 LoginPage**(点按钮再 `signin_redirect`)。
4. **Casdoor 参数 = 构建期 `VITE_*` + `VITE_AUTH_ENABLED` 开关**;运行期注入列为后续。
