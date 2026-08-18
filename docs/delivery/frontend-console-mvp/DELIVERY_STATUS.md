# Frontend Console MVP Delivery Status

## Goal

交付遵循既有管理台设计方案的对账前端管理台与配套查询 API，鉴权延后。

## State

- Phase: completed
- Status: complete
- Last updated: 2026-08-18

## Completed

- 读取仓库进度、CLAUDE、README、MVP 设计、现有 REST、领域模型、JDBC 端口、DDL、测试和 CI。
- 确认仓库与 Git 历史无前端工程或 recon 专属视觉稿。
- 从用户此前相邻管理台方案恢复 React 18/Vite 5/TypeScript/Ant Design 5/React Query/pnpm、视觉 tokens、响应式和测试约定。
- 完成可行性、产品、UI/UX、API、技术、测试、CI、rollout 与 rollback 方案；用户消息已构成范围审批。
- 完成管理台只读查询切片：dashboard 汇总、Run 分页/详情、差异分页/详情、跨表审计/冲正/告警投影和参数校验（AC-01～AC-04）。
- 完成 `recon-console` 工程、统一主题/响应式外壳、工作台、运行管理、差异处理、人工核销/关闭和精确金额展示（AC-05～AC-08）。
- 完成 Vitest/RTL 组件测试与 Playwright 桌面/Pixel 5 冒烟；页面导航、详情和核销链路通过。
- 完成 Docker/Nginx 本地交付配置、双端 CI、根 README/CLAUDE 同步和交付审查文档。
- 完成干净 Maven package、前端锁文件安装/单测/生产构建/浏览器测试，以及 Vite 代理到实际 Spring Boot 包的本地联调。

## Changed Files

- `docs/delivery/frontend-console-mvp/DELIVERY_PLAN.md` - 完整交付方案与 AC。
- `docs/delivery/frontend-console-mvp/DELIVERY_STATUS.md` - 当前执行状态。
- `recon-batch/src/main/java/com/lrj/recon/batch/service/ReconConsoleQueryRepository.java` - 管理台只读投影端口与 DTO。
- `recon-batch/src/main/java/com/lrj/recon/batch/service/ReconConsoleQueryService.java` - 查询参数校验与编排。
- `recon-batch/src/main/java/com/lrj/recon/batch/persistence/JdbcReconConsoleQueryStore.java` - 参数化分页/汇总 SQL。
- `recon-batch/src/main/java/com/lrj/recon/batch/web/ReconConsoleController.java` - 管理台 GET API。
- `recon-batch/src/test/java/com/lrj/recon/batch/web/ReconConsoleControllerTest.java` - H2/MockMvc 契约测试。
- `recon-console/package.json`、`pnpm-lock.yaml` 与构建配置 - 独立 React/Vite 工程。
- `recon-console/src/api/` - 类型化 API 客户端和契约。
- `recon-console/src/components/` - 应用外壳、状态/指标、Run 和差异详情组件。
- `recon-console/src/pages/` - 工作台、运行管理和差异处理页面及组件测试。
- `recon-console/src/theme/`、`src/styles/` - 既定视觉 tokens、桌面/移动响应式样式。
- `recon-console/e2e/console.smoke.spec.ts` - 桌面/移动真实浏览器冒烟。
- `recon-console/Dockerfile`、`nginx.conf.template`、`README.md` - 前端容器和本地/生产同源代理说明。
- `.github/workflows/ci.yml` - Java 21 后端与 Node 20/pnpm/Playwright 前端质量门禁。
- `README.md`、`CLAUDE.md` - 模块、API、命令、金额语义和无鉴权边界。
- `.gitignore` - 忽略 Node/前端构建和测试产物。

## Verification Log

| Command or check | Result | Notes |
| --- | --- | --- |
| `git status --short` | pass | 开始时工作区干净 |
| repository/design discovery | pass | 无现有前端；已有写 API，缺只读查询 API |
| feasibility/adversarial design pass | pass | 无 DDL，查询 JDBC 保持在 persistence |
| `./mvnw -q -pl recon-batch -am -DskipTests compile` | pass | 后端查询切片编译通过 |
| `./mvnw -q -pl recon-batch -am -Dtest=ReconConsoleControllerTest ... test` | pass | 4 个管理台 API 集成测试通过 |
| `./mvnw -q clean package` | pass | 56 个测试套件、205 tests、0 failure/error/skip |
| `pnpm install --frozen-lockfile` | pass | pnpm 9.15.9，锁文件一致 |
| `pnpm test` | pass | 3 files / 4 tests 通过，含 409 乐观锁冲突恢复 |
| `pnpm build` | pass | TypeScript + Vite production build 通过 |
| `pnpm e2e` | pass | desktop Chromium + Pixel 5，2/2 通过 |
| packaged backend + Vite proxy smoke | pass | `/recon/dashboard`、`/recon/runs` 和 SPA fallback 均返回预期结果 |
| CI YAML / Nginx template / secret scan | pass | workflow 可解析；模板变量受限；未发现凭据、TODO 或调试输出 |
| MySQL/PostgreSQL Testcontainers | skipped | 本机 Docker API 未被 Testcontainers 识别；保留在远程 CI 门禁 |

## Decisions And Deviations

- 鉴权按用户要求延后，本轮 operator 仍由人工动作表单提交。
- 不采用 Sites 托管运行时；当前交付是既有 Maven 仓库内的独立 SPA，且用户未授权部署。
- 不为 UI 查询污染 `recon-core`；控制台查询投影留在组合根。
- 新增管理台金额字段使用十进制字符串，避免 JavaScript Number 丢失 BIGINT 精度。

## Blockers And Residual Risks

- 无实施 blocker。
- auth 上线前只能用于受控本地/内网环境，不具备公网安全条件。
- Ant Design 与应用主 chunk 仍超过 Vite 500 kB 告警阈值；不阻塞功能，后续可按路由继续拆包。
- 历史数据量极大时，offset 分页和多列 contains 搜索需要结合生产数据补索引或改 keyset 分页。
- 本机未执行真 MySQL/PostgreSQL 和最终前端 Docker image build；前者由 CI 保留验证，后者需要可用 Docker daemon/Node 基础镜像。

## Next Action

按用户要求最后接入 auth：由可信身份上下文替代请求体 `operator`，再补 SSO/RBAC、接口保护和公网发布门禁。
