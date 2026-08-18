# M5 Processing Closure Delivery Plan

## Requirement

继续 Claude 尚未收尾的 M5：完成处理链、人工核销状态机、告警 outbox、REST、Run 发起与调度的代码审查和修复，同步文档，补齐 CI，并通过本地质量门禁。

## Repository Evidence

- 设计定稿 `docs/design/RECON_MVP_DESIGN.md` 的 M5 清单明确了模块、端点和事务边界。
- `7e42732` 已提交 M4；当前工作区为未提交的 M5 实现。
- 已存在 `recon-handler`、人工核销服务、告警中继、REST、调度及相应测试。
- 基线 `./mvnw -q test` 为 182 tests、0 failures、0 errors。
- 仓库没有 CI 配置，Git 远端为 GitHub。

## Feasibility

- Verdict: go
- Constraints: Java 21；必须使用 `./mvnw`；H2/MySQL/PostgreSQL 方言兼容；保护人工处置和冲正建议；不得在可重试 chunk 内发送外部告警。
- Dependencies: Spring Boot 3.3.5、Spring Batch、JDBC/Flyway、H2 测试库。
- Risks and mitigations:
  - 并发序号或人工处置竞争：审查数据库条件更新并执行并发/乐观锁测试。
  - chunk 重试造成重复副作用：以幂等键和 Job 集成测试验证。
  - outbox 投递竞态或无限重试：审查领取/状态转换和重试上限。
  - 文档滞后：以最终代码和命令同步 README/CLAUDE。

## Product Design

- Actors and goals: 调度系统/运营人员发起和重跑对账；运营人员核销或关闭差异；告警系统可靠接收差异告警。
- Scope: M5 设计定稿列出的处理链、冲正建议、人工处置、告警 outbox、中继、REST、序号分配和定时调度。
- Out of scope: CSV 数据源、真实告警通道、Flowable 工单、鉴权平台、自动资金冲正、M6 全链路加固。
- Business rules: 机器差异、人工处置和冲正建议分表；重跑不删除人工痕迹；冲正仅生成 `SUGGESTED`；人工状态变更使用乐观锁；外部告警在 chunk 提交后投递。

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | 有差组触发幂等处理链；金额型差异生成冲正建议，所有差异生成台账审计和告警 outbox | P0 | handler 单测与 Job 集成测试 |
| AC-02 | chunk 内只写 outbox；批后中继成功置 SENT，失败可补投且受最大次数限制 | P0 | 告警中继单测与 Job 集成测试 |
| AC-03 | 人工 resolve/close 遵循状态机、版本冲突返回 409、重复动作不增加版本或审计 | P0 | core 状态机、服务和 MVC 测试 |
| AC-04 | 重跑保留人工处置和冲正建议；持续差异 re-link，消失或变型差异置 STALE | P0 | A1 收敛集成测试 |
| AC-05 | 发起、重跑、核销、关闭、报表 REST 接口返回稳定成功/400/404/409 契约 | P0 | MockMvc 黑盒测试 |
| AC-06 | 同场景账期并发分配的 Run 序号唯一且连续；REST 与 scheduler 复用同一发起服务 | P0 | 并发测试、装配审查 |
| AC-07 | 单段与营销三方 Job 都接入收敛和告警中继，原有 M0-M4 行为无回归 | P0 | 全量 Maven 测试 |
| AC-08 | 模块依赖满足 ArchUnit，代码可在 Java 21 下 clean package | P0 | ArchUnit、`clean package` |
| AC-09 | README、CLAUDE、交付记录和 GitHub CI 与最终行为及命令一致 | P1 | 文档复核、workflow 语法/底层命令验证 |

## UI/UX Design

- Applicability: Not applicable。仓库没有图形界面；本阶段只新增 REST API。
- API flow: 发起 Run → 查询报表；查询到差异后 resolve/close；冲突与错误使用统一 `{error,message}`。
- State matrix: 正常返回 2xx；参数错误 400；资源不存在 404；非法流转或版本冲突 409。
- Accessibility/responsive: Not applicable。

## Technical Solution

- Chosen approach: 保留六边形分层；纯 Java handler/state machine 位于外圈或 core，Spring/JDBC/Batch 装配留在 `recon-batch`；处理链在 writer 的 chunk 事务内写 DB，告警由批后 relay 发送。
- Alternatives rejected:
  - chunk 内直发告警：重试会产生不可回滚的重复副作用。
  - `MAX(sequence_no)+1`：存在并发竞态。
  - 重跑覆盖 disposition：违反人工痕迹保护红线。
- Modules and file map: `recon-core` 状态机/模型；`recon-handler` 处理链；`recon-batch` JDBC、服务、Job、REST、调度；`README.md`、`CLAUDE.md`、`.github/workflows/ci.yml`。
- Contracts and data: 复用 V1 既有 `discrepancy_action`、`recon_run_seq`、`alert_outbox`、`discrepancy_disposition.version`；不新增迁移。
- Security and reliability: operator 当前来自请求体是已声明的 MVP 限制；审查输入长度、并发、幂等、事务和 outbox 重试。
- Observability: relay/收敛/调度使用结构化上下文日志；真实监控留 M6/生产接入。
- Compatibility and migration: 不改变既有表结构；M0-M4 API/Job 保持兼容。

## Implementation Sequence

1. 审查领域状态机、端口和 handler，修复确认问题（AC-01/03/08）。
2. 审查 JDBC、事务、序号和 outbox 并发语义，补测试（AC-02/04/06）。
3. 审查 Job/REST 装配和错误契约，补集成/黑盒覆盖（AC-05/07）。
4. 同步文档并增加最小 GitHub CI（AC-09）。
5. 运行 targeted tests、全量测试、clean package 和最终 diff 审查。

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-01/02 | unit + integration | handler、relay、HandlerChainJob 测试 | 幂等条数、状态与投递时序断言 |
| AC-03/04 | domain + integration | StateMachine、ManualClearing、Convergence 测试 | 状态、version、审计和保留断言 |
| AC-05 | API | DiscrepancyControllerTest | 2xx/400/404/409 响应 |
| AC-06 | concurrency | ReconRunSeqConcurrencyTest | 唯一连续序号 |
| AC-07/08 | regression/build | `./mvnw -q test`、`./mvnw -q clean package` | 零失败并产出包 |
| AC-09 | delivery | workflow inspection + local underlying command | 文档一致、CI 命令本地通过 |

## Documentation Plan

- 更新 README 模块图、路线图、M5 API/配置/运行说明。
- 更新 CLAUDE 模块数量、构建说明和当前进度。
- 生成 review、QA、delivery 报告。

## CI Plan

- 新增 GitHub Actions，使用 Temurin Java 21 与 Maven cache，运行仓库 wrapper 的 clean package。
- 不加入部署、密钥或外部环境操作。

## Rollout And Rollback

- Rollout: 先保持 scheduler 默认关闭；按环境配置显式启用；真实告警通道用替换 Bean 接入。
- Monitoring: 关注 Job 终态、outbox FAILED/attempt、STALE 数和 API 409 比率。
- Rollback: 回退 M5 应用代码并保持 scheduler 关闭；既有 V1 表结构不需回滚；已生成人工/冲正/告警记录不做破坏性删除。

## Assumptions And Open Decisions

- 本次“继续执行”视为继续既有 M5 设计范围的批准，不扩展到 M6。
- 不执行 Git commit/push/deploy；这些需要单独明确授权。
- 真实 MySQL/PostgreSQL 与真实告警通道验证不在当前本地环境内，若未能执行会记录为外部风险。

## Approval

- Status: approved
- Approved scope: 完成上一轮识别出的 M5 未收尾工作。
- Evidence: 用户消息“继续执行claude未执行的任务”。
