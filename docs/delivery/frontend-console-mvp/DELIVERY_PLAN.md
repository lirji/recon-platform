# Frontend Console MVP Delivery Plan

## Requirement

在不接入认证鉴权的前提下，交付可本地联调的对账前端管理台和配套查询接口。视觉与工程约定沿用用户此前管理台方案；现有 Run 发起、重跑、人工核销和报表接口保持兼容。

## Repository Evidence

- 当前仓库是 Java 21 / Spring Boot 3.3.5 Maven 多模块，唯一组合根为 `recon-batch`，无前端模块、`package.json` 或静态页面。
- 已有 `/recon/runs` 发起、`/rerun`、差异 `/resolve|close` 和 `/report`，但没有运行分页、差异查询、详情审计或仪表盘汇总接口。
- `recon_run`、`discrepancy`、`discrepancy_disposition`、`discrepancy_action`、`reversal_suggestion` 和 `recon_report` 已含管理台所需数据，不需要数据库迁移。
- 用户此前相邻管理台方案统一采用 React 18、TypeScript、Vite 5、Ant Design 5、React Query、pnpm，主色 `#315EFB`、224/72 侧栏、1440 内容宽度、桌面表格/移动卡片、Vitest 与 Playwright。

## Feasibility

- Verdict: go
- Constraints:
  - 鉴权明确延后；当前人工动作仍由表单填写 `operator`，axios 客户端保留未来统一注入令牌的位置。
  - 不改变领域表结构，不把查询 DTO/框架依赖放入 `recon-core`。
  - 本轮不部署、不提交、不推送；只交付 CI-ready 代码。
- Dependencies:
  - Node.js、pnpm；前端通过 Vite `/recon` proxy 联调本地 `recon-batch:8080`。
  - Spring JDBC 查询实现继续限定在 `recon-batch.persistence`。
- Risks and mitigations:
  - 无鉴权时写接口不适合暴露公网：README 明确只用于受控本地/内网，生产前必须完成 auth。
  - 跨 Run fingerprint 共享人工处置：查询按现有领域语义 join，不复制或改写状态。
  - 大表查询：所有列表强制分页、限制 `size<=100`、使用固定排序和参数化过滤。

## Product Design

- Actors and goals: 对账运营人员快速掌握运行健康度、发起或重跑任务、定位差异、查看血缘与审计、执行核销/关闭。
- Scope:
  - 工作台：Run/差异关键指标、差异类型构成、最近运行。
  - 运行管理：筛选、分页、发起、重跑、详情和守恒报表。
  - 差异处理：筛选、分页、详情、血缘、处置状态、审计、冲正建议、核销和关闭。
  - 配套只读 API、响应式页面、自动化测试、README 与 CI。
- Out of scope:
  - 登录、SSO、RBAC、租户隔离和公网暴露。
  - Flowable、Drools、真实告警配置、自动冲正、生产部署。
  - 新数据库表、数据迁移、编辑对账场景。
- Business rules:
  - 未存在 `discrepancy_disposition` 的差异在 UI/API 中显示为合成状态 `OPEN`。
  - 人工操作继续传 `expectedVersion`；没有处置记录时为 `null`，冲突返回 409 并刷新详情。
  - 金额在服务端始终是 signed `long` minor units；查询 API 以十进制字符串返回，前端只格式化展示，不参与浮点业务计算。
  - Run、差异列表均为 0-based page，默认 20 条，最大 100 条。

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | `GET /recon/dashboard` 返回运行/差异指标、类型分布和最近 Run | P0 | MockMvc + H2 集成测试 |
| AC-02 | `GET /recon/runs` 支持状态/场景/账期筛选与稳定分页 | P0 | MockMvc 边界与分页测试 |
| AC-03 | `GET /recon/discrepancies` 支持 Run、类型、处置状态、分段、币种和关键字筛选 | P0 | MockMvc + SQL 投影断言 |
| AC-04 | `GET /recon/discrepancies/{id}` 返回机器差异、处置、动作审计、冲正建议和告警状态 | P0 | MockMvc 详情测试 |
| AC-05 | 管理台工作台能展示指标、差异构成和最近 Run，并正确处理 loading/empty/error | P0 | Vitest 组件测试 + Playwright |
| AC-06 | 运行页能筛选分页、发起/重跑并查看守恒报表 | P0 | Vitest + Playwright mock API |
| AC-07 | 差异页能筛选分页、查看血缘/审计并核销或关闭，409 时提示刷新 | P0 | Vitest 交互测试 |
| AC-08 | 390px 手机宽度使用卡片/抽屉，桌面使用表格；键盘焦点和语义标签可用 | P1 | Playwright 桌面/移动视口 |
| AC-09 | 后端全量 Maven 与前端 typecheck/test/build 通过，CI 同时覆盖两端 | P0 | 本地命令 + workflow 解析 |

## UI/UX Design

- Applicability: applicable；新增完整用户界面。
- Flow and component map:

```text
应用外壳（品牌区 / 折叠侧栏 / 顶栏）
├── 工作台 /dashboard
│   ├── 指标卡（总运行、进行中、失败/不平衡、开放差异）
│   ├── 差异类型环图
│   └── 最近运行
├── 运行管理 /runs
│   ├── 筛选与发起按钮
│   ├── 桌面表格 / 移动卡片
│   └── Run 详情抽屉（元数据 + 守恒报表 + 重跑）
└── 差异处理 /discrepancies
    ├── 多条件筛选
    ├── 桌面表格 / 移动卡片
    └── 差异详情抽屉（金额、血缘、处置、审计、冲正/告警 + 核销/关闭）
```

- Visual language:
  - 沿用既定 Ant Design 管理台语言：primary `#315EFB`、success `#16A36A`、warning `#D97706`、error `#D92D20`、layout `#F5F7FA`、text `#172033`。
  - 白色信息面板、8/12 圆角、轻边框和克制阴影；状态颜色只承载语义，不使用装饰性插画。
  - 运营首屏优先呈现“异常是否需要处理”，避免通用欢迎页。
- State matrix:
  - Loading: 首屏骨架、表格局部 loading、提交按钮禁用。
  - Empty: 区分“没有数据”和“筛选无结果”，提供清除筛选/发起任务入口。
  - Error: 页面内重试；400/404/409 映射为中文可恢复提示，不直接吐服务端堆栈。
  - Success: 发起/重跑/核销后提示并 invalidate 相关查询；不做与服务端不一致的乐观删除。
- Responsive and accessibility behavior:
  - `>=992px` 使用 224/72 折叠侧栏和表格；`<768px` 使用顶部菜单、卡片列表、全宽抽屉/弹窗。
  - 触控目标至少 44px；所有图表有文本摘要，状态不只依赖颜色；表单有 label、错误提示与自动聚焦。

## Technical Solution

- Chosen approach:
  - 新建独立 `recon-console` SPA，React Router 管路由、React Query 管服务端状态、Ant Design 提供组件、ECharts 展示类型构成。
  - 后端新增 `ReconConsoleQueryService`（校验/编排）和 `JdbcReconConsoleQueryStore`（参数化分页 SQL），Controller 仅做 HTTP DTO 映射。
  - Vite dev proxy 与 Nginx `/recon` 反向代理维持同源，不开启宽松 CORS。
- Alternatives rejected:
  - Thymeleaf/服务端模板：与用户既有独立 SPA 方案不一致，响应式和后续鉴权演进成本高。
  - Sites 托管运行时：会引入独立托管/部署配置，与现有 Maven 单仓和本轮“不部署”边界不一致。
  - 在 core 扩展 UI 查询端口：控制台投影不是领域不变量，会污染纯领域内核。
- Modules and file map:
  - `recon-batch/.../service/ReconConsoleQueryService.java`
  - `recon-batch/.../persistence/JdbcReconConsoleQueryStore.java`
  - `recon-batch/.../web/ReconConsoleController.java`
  - `recon-batch/src/test/.../ReconConsoleControllerTest.java`
  - `recon-console/`：Vite 配置、API/types、theme/layout、dashboard/runs/discrepancies 页面、测试、Docker/Nginx。
  - `.github/workflows/ci.yml`、`README.md`、`CLAUDE.md`。
- Contracts and data:
  - 通用 `PageResponse<T>{content,page,size,totalElements,totalPages}`。
  - 日期使用 ISO-8601；金额字段使用十进制字符串承载 minor units，避免 JavaScript `Number` 丢失 BIGINT 精度。
  - 查询无迁移，直接读取既有表并通过 fingerprint 关联处置/动作/建议/告警。
- Security and reliability:
  - 本轮不实现 auth，但不加 `permitAll` 安全配置，也不把 operator 固化在代码；未来在 axios 拦截器和 Spring Security 上叠加。
  - 搜索长度、页码、page size、枚举均校验；动态 SQL 只拼固定子句，值全部绑定参数。
- Observability: 保留统一 `{error,message}`；前端为请求错误提供可恢复反馈。查询接口不记录敏感 payload 到浏览器日志。
- Compatibility and migration: 只新增 GET API；现有 POST/GET 合约不变；无 DDL。

## Implementation Sequence

1. 后端查询投影与 API，覆盖 AC-01～AC-04。
2. 前端工程骨架、主题、API 客户端和响应式外壳，覆盖 AC-05/AC-08 基础。
3. 工作台与运行管理垂直切片，覆盖 AC-05/AC-06。
4. 差异工作台和人工动作闭环，覆盖 AC-07。
5. 端到端测试、审查修复、文档与 CI，覆盖 AC-08/AC-09。

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-01～04 | backend integration | `./mvnw -q -pl recon-batch -am test` | MockMvc + H2 结果 |
| AC-05～07 | frontend component | `pnpm test` | Vitest/RTL 交互断言 |
| AC-08 | browser QA | `pnpm e2e` | desktop + mobile smoke |
| AC-09 | full build | `./mvnw -q clean package`; `pnpm build` | 退出码 0、产物存在 |
| SQL portability | static + existing real DB CI | MySQL/PostgreSQL Testcontainers job | 远程 CI；本地 Docker 不可用则 conditional |

## Documentation Plan

- README 增加前端启动、代理、页面能力、无鉴权风险和生产反代说明。
- CLAUDE 增加 `recon-console` 模块、命令、API/query 约束和 auth 延后边界。
- 新增 delivery plan/status/review/QA/report。

## CI Plan

- 保留 Java 21 Maven 构建与真库方言测试。
- 新增 Node 20 + pnpm，执行前端 install、test、build；Playwright 冒烟在安装 Chromium 后运行。
- 不加入部署、secrets 或环境写入。

## Rollout And Rollback

- Rollout: 先用 Vite proxy 联调；生产化时由 Nginx 托管 `dist` 并同源代理 `/recon`。鉴权上线前不得暴露公网。
- Rollback: 前端可独立下线；新增 GET 接口无数据写入，回退代码不需迁移。人工动作沿用原有 API，已提交的处置不可由前端回滚。

## Assumptions And Open Decisions

- 已按用户此前方案恢复 React 18/Vite 5/Ant Design 5/React Query/pnpm 和既定视觉 tokens；仓库本身没有单独的 recon 前端稿。
- 管理台 MVP 只覆盖现有单场景 `MARKETING_3WAY`，但查询筛选与 DTO 不把场景写死。
- auth 最后接入；届时 operator 从可信身份上下文获取并移除请求体信任。

## Approval

- Status: approved
- Approved scope: 前端管理台和相关接口；auth 鉴权最后实施。
- Evidence: 用户消息“接入auth鉴权最后做，先做前端管理台和相关接口。前端设计按照我之前定的一些方案来实施”。
