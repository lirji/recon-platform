# Frontend Console MVP QA Report

## Verdict

本地功能、构建和 Docker 部署质量门禁通过；远程 CI 与真 MySQL/PostgreSQL 仍为条件验证。当前版本适合 loopback 本地联调，不适合在无 auth 情况下公网发布。

## Test Environment

- Java 21.0.11、Spring Boot 3.3.5、H2 内存库。
- Node 24 本地运行、pnpm 9.15.9；CI 固定 Node 20。
- Chromium desktop 与 Pixel 5 viewport。
- Docker Desktop 29.7.2；Compose 项目 `recon-platform`，后端/管理台双容器和具名 H2 卷。
- 日期：2026-08-18，Asia/Taipei。

## Acceptance Evidence

| AC | Result | Evidence |
| --- | --- | --- |
| AC-01 Dashboard API | pass | MockMvc/H2 验证指标、类型分布和最近 Run |
| AC-02 Run 查询 | pass | 状态/场景/账期、稳定分页、详情与 400/404 边界 |
| AC-03 差异查询 | pass | Run/type/status/segment/currency/query 过滤和分页 |
| AC-04 差异详情 | pass | 机器结果、处置、动作、冲正和告警投影 |
| AC-05 工作台 UI | pass | loading/empty/error 组件状态；浏览器可见指标与图表摘要 |
| AC-06 运行管理 UI | pass | 发起、筛选、详情、守恒报表和重跑入口 |
| AC-07 差异闭环 UI | pass | 详情、血缘、审计、核销/关闭；409 刷新恢复测试 |
| AC-08 响应式/可访问性 | pass | desktop table + Pixel 5 card/drawer；语义按钮、label、焦点样式 |
| AC-09 构建与 CI | conditional-pass | 本地双端全通过、workflow 可解析；远程 workflow 已由 push 触发但本机无 GitHub API 凭据读取结论 |

## Command Results

| Command/check | Result |
| --- | --- |
| `./mvnw -q clean package` | pass；56 suites / 205 tests / 0 failures / 0 errors / 0 skipped |
| `pnpm install --frozen-lockfile` | pass |
| `pnpm test` | pass；3 files / 4 tests |
| `pnpm build` | pass；TypeScript 与 Vite production bundle 已生成 |
| `pnpm e2e` | pass；desktop Chromium 与 Pixel 5，2/2 |
| packaged Spring Boot + Vite proxy | pass；dashboard、Run 空分页、SPA fallback |
| workflow YAML parse | pass |
| whitespace / credential / TODO / debug scan | pass |
| MySQL/PostgreSQL Testcontainers | skipped；本机 Docker API 未被 Testcontainers 识别 |
| `docker compose up -d --build --remove-orphans` | pass；前后端镜像完整重建并启动 |
| container health | pass；`recon-platform-backend` 与 `recon-platform-console` 均 healthy |
| deployed HTTP smoke | pass；`/healthz`、SPA fallback、代理/直连 dashboard |
| runtime boundary | pass；8088/8180 仅绑定 `127.0.0.1`，后端非 root，H2 文件位于具名卷 |

## Bundle Evidence

- React chunk：61.09 kB，gzip 20.90 kB。
- 应用 chunk：610.61 kB，gzip 208.40 kB。
- Ant Design chunk：1,082.08 kB，gzip 337.94 kB。
- 构建成功，但后两者超过 Vite 500 kB 提示阈值，已进入后续性能优化项。

## Regression And Failure-path Coverage

- 后端覆盖非法枚举/日期/分页、not found 和金额字符串契约。
- 前端覆盖空/错/加载状态、详情拉取、提交 `expectedVersion`、409 冲突刷新。
- 浏览器流程覆盖跨页面导航、桌面/移动布局、打开 Run/差异详情和人工核销成功提示。
- 未做生产 SSO/RBAC、真实生产数据规模压测和远程 CI 真库运行；这些需要后续范围或外部环境。
