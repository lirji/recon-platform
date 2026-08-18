# Frontend Console MVP Delivery Report

## Outcome

已完成对账前端管理台和相关查询接口，沿用既定 React/Ant Design 管理台方案；auth 按要求保留到最后实施。代码已完成本地全量构建、自动化测试和真实本地代理联调。

## Delivered Capabilities

- 工作台：运行健康指标、待处理差异、差异类型构成、最近 Run 和发起入口。
- 运行管理：场景/账期/状态筛选、分页、发起、重跑、详情和守恒报表。
- 差异处理：多条件筛选、分页、详情、原始血缘、处置审计、冲正建议、告警状态、核销和关闭。
- 响应式体验：桌面侧栏/表格，移动菜单/卡片/全宽抽屉，统一 loading/empty/error/success 状态。
- 后端 API：dashboard、Run 分页/详情、差异分页/详情；既有写 API 保持兼容。
- 工程交付：pnpm 锁文件、Vitest、Playwright、Docker/Nginx 配置、双端 GitHub Actions CI 和文档。
- 本地部署：后端/管理台双镜像、健康检查、loopback 端口和具名 H2 数据卷，两个容器均为 healthy。

## API Additions

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/recon/dashboard` | 管理台汇总、类型分布、最近 Run |
| GET | `/recon/runs` | Run 条件查询与分页 |
| GET | `/recon/runs/{id}` | Run 元数据与守恒报表 |
| GET | `/recon/discrepancies` | 差异多条件查询与分页 |
| GET | `/recon/discrepancies/{id}` | 差异、处置、审计、冲正和告警详情 |

所有金额字段以 minor units 十进制字符串返回，避免 JavaScript BIGINT 精度损失。

## Quality Gate Summary

- 后端：205 tests，0 failures/errors/skips；干净 package 通过。
- 前端：4 unit/component tests、2 browser tests 全通过；TypeScript 与 production build 通过。
- 集成：打包后的 Spring Boot 服务经 Vite `/recon` proxy 返回正确 dashboard/分页 JSON，SPA fallback 正常。
- 审查：批准范围内无阻塞问题；参数化 SQL、输入边界、乐观锁恢复和金额精度已覆盖。
- Docker：前后端镜像完整构建，Compose 部署与 HTTP smoke 通过；本机 Testcontainers 因 Docker 29 API 协商问题仍跳过真库用例。
- 条件项：GitHub CLI 未配置 API 凭据，无法从本机确认远程 CI 结论；真库作业继续以远程 runner 结果为准。

## Operational Notes

- 本地后端：`./mvnw -pl recon-batch -am spring-boot:run`。
- 本地前端：`cd recon-console && pnpm install && pnpm dev`。
- 默认前端代理到 `http://localhost:8080`；可通过 `VITE_RECON_API_TARGET` 覆盖。
- `docker compose up -d --build --remove-orphans` 同时构建并部署双端；管理台为 `http://localhost:8088`，后端诊断地址为 `http://127.0.0.1:8180`。
- 容器运行时用 `RECON_API_URL=http://backend:8080` 指向内部后端；8088/8180 均只绑定 loopback，Nginx 提供 SPA 与同源 `/recon` 代理。
- `recon-platform-data` 具名卷保存 H2 文件，常规重建/`docker compose down` 不删除数据。
- auth 上线前仅限受控本地/内网，不得直接公网暴露写接口。

## Deferred Work

下一阶段接入 auth：Spring Security、SSO/会话或令牌、RBAC、可信 operator 注入、前端登录态与 401/403 路由保护。完成后再进行公网发布、远程 CI/容器构建和生产数据规模验证。
