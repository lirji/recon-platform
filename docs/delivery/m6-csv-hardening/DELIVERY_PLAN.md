# M6 CSV 与全链路加固交付计划

## 目标与依据

- 目标：完成 `recon-source-csv` 外圈适配器，将 CSV 数据源接入营销三方两段 Job，并以 H2 验证重跑、人工核销保护、守恒和全部 MVP 差异类型。
- 权威设计：`docs/design/RECON_MVP_DESIGN.md` 的 §11 M6 与 §12 验收标准。
- 启动指令：用户在 M5 完成后要求“做下一个”，视为批准进入已命名的下一里程碑 M6。
- 基线：M5 收官时全量 189 个测试通过，`./mvnw -q clean package` 通过。

## 范围

### 本轮包含

1. 新增纯外圈模块 `recon-source-csv`，实现 `SourceAdapter` 惰性前向游标。
2. 支持 UTF-8/UTF-16 BOM、显式字符集、可配置单字符分隔符、表头列映射、CSV 引号和跨行字段。
3. 标准化失败按行进入 `RejectedRow`，保留 `文件:物理行号` 血缘且不中断后续可读记录。
4. 在组合根增加源适配器路由，保持现有 DB 配置兼容，并允许营销三方两段场景切换到 CSV 文件。
5. 用 H2 做 CSV→Batch→差异→处理链→报表全链路测试，覆盖重跑幂等、人工核销保护与双向守恒。
6. 固化 MVP 差异分类矩阵，证明 AMOUNT_MISMATCH、MISSING、DUPLICATE、EXTRA、BRIDGE_BROKEN、GROUP_SUM_MISMATCH、CURRENCY_MISMATCH、STATUS_MISMATCH、TIMING 可复现。
7. 为新外圈模块添加 ArchUnit 门禁，更新文档与 CI 真库验证步骤。

### 本轮不包含

- 生产文件传输、对象存储、API/MQ 数据源。
- Drools、Flowable、自动资金冲正和汇率换算。
- 连接生产数据库或部署；MySQL/PostgreSQL 仅在可用 Docker/CI 环境运行既有 Testcontainers 测试。
- 性能压测与千万级容量证明，留作下一里程碑。

## 验收条件

| ID | 验收条件 | 证据 |
|---|---|---|
| AC-01 | `recon-source-csv` 仅依赖 core 与 CSV 解析库，记录不全量 materialize；reject 列表边界如实记录 | 单元测试、ArchitectureTest、Known Issues |
| AC-02 | BOM、字符集、分隔符、表头映射、引号与跨行字段正确解析 | CsvSourceAdapterTest |
| AC-03 | 畸形业务行进入 reject，含文件和行号，后续记录继续处理 | 单元测试、Batch 集成测试 |
| AC-04 | DB/CSV 由统一路由选择，默认 DB 行为不回归；三方场景可配置 CSV | 路由测试、既有回归测试 |
| AC-05 | CSV 三方数据能跑完两段 Job，产出差异、血缘和闭合报表 | MarketingThreeWayCsvEndToEndTest |
| AC-06 | 同一 Run 重跑结果幂等，RESOLVED/CLOSED 人工处置与冲正建议不被覆盖 | M6 集成测试 |
| AC-07 | 设计要求的九个 MVP 差异类别（含两段 BRIDGE_BROKEN）均有可执行证据 | 验收矩阵测试/现有专项测试 |
| AC-08 | 全模块测试、ArchUnit、`clean package` 与静态差异检查通过 | QA_REPORT.md |
| AC-09 | CI 显式尝试 MySQL/PostgreSQL Testcontainers 验证；Docker 不可用时本地可判定跳过 | workflow、QA_REPORT.md |

## 实施顺序

1. 建模块、配置对象、BOM 识别、CSV 游标与单元/架构测试。
2. 建 `RoutingSourceAdapter`，接入 Spring 组合根和场景 CSV 描述符。
3. 增加 CSV 三方全链路验收与差异分类矩阵。
4. 做代码审查，修复发现的问题并同步 README、CLAUDE、已知问题和 CI。
5. 跑目标测试、全量测试、真库可选测试和干净打包，产出审查/QA/状态报告。

## 风险与控制

- CSV 语法错误可能令解析器无法安全恢复：语法级错误将记录 reject 后终止该文件；字段语义错误可逐条拒绝并继续。文档明确边界，避免伪称完全恢复。
- 同一账务文件会按两个 segment 投影读取：`recordId` 包含 run/segment/side，`rawRef` 保持文件行血缘，避免主键冲突。
- 多个 `SourceAdapter` Bean 会造成注入歧义：组合根提供唯一 `@Primary` 路由器，具体适配器仍保留独立 Bean 便于测试与扩展。
- BOM 与显式字符集冲突会造成静默乱码：检测到冲突时 fail-fast。

## 审批记录

- 当前状态：已批准。
- 依据：用户明确要求在 M5 后“做下一个”，而项目路线图唯一下一项为 M6。
