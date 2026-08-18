# Frontend Console MVP Review Report

## Verdict

批准范围内无阻塞缺陷。前端管理台和查询接口可以进入受控本地/内网联调；在 auth 完成前禁止公网发布。

## Reviewed Scope

- Dashboard、Run 列表/详情、差异列表/详情及人工核销/关闭交互。
- 新增只读 API、参数校验、分页 SQL、金额/时间契约和统一异常反馈。
- 响应式布局、无障碍基础、前端状态管理、Nginx 同源代理和 CI。
- 既有 Run 发起、重跑、报表和人工处置写接口的兼容性。

## Findings Resolved

| Severity | Finding | Resolution |
| --- | --- | --- |
| High | Java `BIGINT` 直接映射 JavaScript `Number` 会丢失金额精度 | 所有管理台金额投影改为十进制字符串，格式化逻辑基于字符串/`BigInt` |
| High | 人工处置存在乐观锁冲突，旧详情可能覆盖新状态 | 请求携带 `expectedVersion`；409 后刷新详情并显示可恢复提示 |
| High | 初版汇总曾把自动关闭语义的 `STALE` 误计为待处理 | Dashboard 和 Run 汇总只将无处置记录与 `REOPENED` 计为待处理，并增加 `REOPENED`/`STALE` 回归断言 |
| Medium | UTC 日期默认值在本地时区可能错一天 | 发起 Run 默认日期改用浏览器本地日期 |
| Medium | 长期保存 operator 会扩大共享终端暴露面 | 仅保存到 `sessionStorage`，关闭会话后清除；auth 接入后删除该信任路径 |
| Medium | 不受限页码/页大小可能造成整数溢出或大查询 | `page` 限制 0～1,000,000，`size` 限制 1～100，所有值参数化 |
| Medium | 差异按机器时间排序不能反映最近人工动作 | 排序改为 `COALESCE(disposition.updated_at, discrepancy.updated_at)` |
| Low | 生产 source map 可能暴露源码 | Vite production sourcemap 关闭 |
| Low | 全量 ECharts 引入造成不必要体积 | 改用 ECharts core 按需注册；保留 Ant Design/主包体积优化项 |

## Security And Reliability Review

- 动态 SQL 只拼接代码内固定条件，用户值全部使用 JDBC 占位符。
- 枚举、ISO 账期、币种、文本长度、页码和 page size 均在 service 边界校验。
- 查询 DTO 不进入 `recon-core`，没有改动领域不变量或数据库结构。
- API 客户端不记录敏感 payload；错误显示使用可恢复中文提示，不输出后端堆栈。
- 前端与 API 使用同源 `/recon` 路径，没有新增宽松 CORS。
- auth 是明确延期项；当前 `operator` 是声明值，不是可验证身份。

## Residual Risks

| Risk | Impact | Follow-up |
| --- | --- | --- |
| auth/RBAC 尚未接入 | 若暴露公网，任何调用者都可执行写操作 | 发布前接 Spring Security + SSO/RBAC，并从身份上下文生成 operator |
| offset 分页与多列 contains 搜索 | 大历史表可能出现慢查询 | 用生产基数做 `EXPLAIN`，按需补索引、搜索列或 keyset 分页 |
| 主 chunk 体积告警 | 首次加载可能偏慢 | 路由级 lazy import，并继续拆分 Ant Design/页面模块 |
| 真库查询投影未在本机执行 | MySQL/PostgreSQL 方言差异仍需环境证据 | 依赖 CI 真库作业；后续给控制台查询增加真库 smoke fixture |
| Docker image 未在本机构建 | 容器产物仍需 CI/可用 daemon 验证 | 在镜像构建流水线执行 `docker build` 与 `/healthz` 冒烟 |

## Compatibility Result

- 仅新增 GET API，没有 DDL 或破坏性迁移。
- 既有 POST 发起/重跑/核销/关闭和 GET 报表合约未修改。
- 回滚前端或新增查询代码不需要数据回滚；已发生的人工处置仍遵循原有审计语义。
