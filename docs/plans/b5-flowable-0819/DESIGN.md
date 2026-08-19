# B5 · Flowable 工单/审批工作流 — 设计定稿

> 路线图 Track B · B5(P2 · 工作量 M–L)。目标:落地**处置/冲正审批工作流**。
> 现状:`recon-handler` 的 `FlowableTicketHandler` 是 no-op 占位(`supports()` 恒 false);冲正只到 `SUGGESTED`,无审批路径。
> 依赖:A1 鉴权(已就绪)。与 B3 互为支撑(B3 自动冲正执行需审批,B5 提供审批)。

## 1. 硬约束

- **ArchUnit 红线**:`org.flowable..` 禁进 recon-core / recon-handler / 其它纯 Java 圈。→ Flowable 落**外圈新模块**。
- **不破坏既有绿**:Flowable 引擎重(Liquibase 建 ~25 张 `ACT_*` 表 + 引擎 bootstrap)。**必须 config 门控默认关**,
  否则每个 `@SpringBootTest`(128 例)都要建 Flowable schema、拖慢 + 风险。默认关时行为与现状完全一致。
- **保护人工痕迹(ADR-7)**:审批只改 `reversal_suggestion.status`(SUGGESTED→CONFIRMED/DISCARDED),不删不碰其它。

## 2. 选定的工作流:冲正审批

- 冲正建议(`ReversalSuggestion`,状态 `SUGGESTED`)提交审批 → 审批人 approve/reject → `CONFIRMED`/`DISCARDED`。
- BPMN:`start → 人工审批 UserTask → exclusive gateway(approved?)→ confirmedEnd / discardedEnd`。
- 结束时经执行监听器回调 Java 更新 `reversal_suggestion.status`。**无资金动作**(资金动作归 B3)。

## 3. 核心决策

### D1 · 新模块 `recon-workflow-flowable`(外圈)
- 依赖:recon-core(SPI/模型)+ Flowable 引擎(`org.flowable:flowable-engine` 7.0.x,兼容 Spring Boot 3 / Jakarta / Java 21)。
- 内容:BPMN 资源、`ReversalApprovalWorkflow`(封装 Flowable RuntimeService/TaskService)、结束回调 `ReversalStatusListener`。
- 自带 ArchUnit:允许 `org.flowable..`,禁 Spring Batch/CSV/Drools(纯工作流库;Spring 装配归组合根)。
- 加入根 pom `<module>`。

### D2 · 引擎 config 门控(默认关)
- **不用 flowable-spring-boot-starter**(它无条件 auto-config 引擎)。改**手动** `SpringProcessEngineConfiguration` bean,
  `@ConditionalOnProperty(recon.workflow.flowable.enabled=true)`,默认关。用应用 DataSource + `databaseSchemaUpdate=true`
  让 Flowable 自建 `ACT_*`(仅启用时)。关闭时引擎不加载、无 ACT 表、REST/handler 退化,现有 128 测试零影响。

### D3 · 组合根装配(recon-batch)
- `recon-batch` 依赖 `recon-workflow-flowable`;`WorkflowConfig` 条件装配 ProcessEngine + Repository/Runtime/Task Service + 部署 BPMN。
- `ReversalApprovalService`(batch service):`submit(reversalId)`→启流程;`listPending()`→待办;`decide(taskId, approved, operator)`→完成任务。
- 结束监听器调 `ReversalSuggestionRepository.updateStatus(id, CONFIRMED|DISCARDED, operator)`(新增端口方法 + JDBC 实现)。
- REST:`GET /recon/reversal-approvals`(待办,recon.read)、`POST /recon/reversal-approvals/{taskId}/decide`(recon.dispose)。

### D4 · fail-safe
- 未启用却调审批 API → fail-fast(`UnsupportedOperationException`/400「工作流未启用」),绝不静默无操作。
- BPMN 部署失败 → 启动 fail-fast(仅启用时)。

## 4. 组件与文件

| 交付 | 位置 |
|---|---|
| 新模块 pom | `recon-workflow-flowable/pom.xml`(+ 根 pom module) |
| BPMN | `recon-workflow-flowable/src/main/resources/processes/reversal-approval.bpmn20.xml` |
| 工作流封装 | `.../workflow/ReversalApprovalWorkflow.java`、`ReversalStatusListener.java` |
| ArchUnit | `.../test/.../ArchitectureTest.java` |
| 端口扩展 | `recon-core` `ReversalSuggestionRepository.updateStatus(...)` + `JdbcReversalSuggestionStore` 实现 |
| 组合根 | `recon-batch` `config/WorkflowConfig.java`(条件引擎)+ `service/ReversalApprovalService.java` + `web/ReversalApprovalController.java` |
| 测试 | 门控 `@SpringBootTest(properties=enabled=true)` 跑一条审批到 CONFIRMED;ArchUnit |

## 5. 测试策略

- **门控**:默认关时现有 128 测试不加载 Flowable(验证:普通 @SpringBootTest 无 ACT 表、无引擎 bean)。
- **审批闭环**(启用):seed 一条 SUGGESTED 冲正 → submit → listPending 有任务 → decide(approved) → 状态 CONFIRMED;reject → DISCARDED。
- **fail-fast**:未启用调 API → 异常。
- ArchUnit 新模块 + recon-core/handler 门禁不破。
- 全量 `./mvnw test` 8→9 模块绿。

## 6. 非目标(本里程碑)

- 不做资金动作(冲正真实执行归 B3)。
- 不做处置(核销/关闭)工作流化(现有 ManualClearingService 直改够用);B5 聚焦冲正审批闭环打样。
- 不做流程版本管理/会签/多级审批(单级审批打样,后续可扩)。
- 不做前端审批页(本期后端 + API;UI 后续走 frontend-plan)。

## 7. 风险与回滚

- 风险:Flowable 依赖重、schema 自建与 Flyway/Batch 并存(隔离在 ACT_* 命名空间,不冲突);启用才付代价。
- 回滚:`recon.workflow.flowable.enabled=false`(默认)即完全绕开;新模块不被引用即死代码。
