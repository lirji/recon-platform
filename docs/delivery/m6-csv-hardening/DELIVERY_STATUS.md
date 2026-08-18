# M6 CSV 与全链路加固状态

## 当前状态

`COMPLETE`

## 已完成

- 读取 M5 交付状态、权威设计、模块依赖、数据源 SPI、Batch 接线和测试基线。
- 明确 M6 范围、非目标、风险与 AC-01～AC-09 验收条件。
- 确认采用独立 CSV 外圈模块、组合根路由和三方场景可配置 CSV 的方案。
- 完成 `recon-source-csv`、组合根路由、格式无关场景描述符与三方 CSV 配置。
- 完成九类差异、两段断链、reject、守恒、重跑和人工处置保护的 H2 全链路验收。
- 修复 reject 重跑累积、非法编码静默替换、reject reason 超长和 recordId 冗余问题。
- 同步 README、CLAUDE、Known Issues、application.yml 与 GitHub CI。
- 最终 55 suites / 201 tests 全绿，6 模块 `clean package`、ArchUnit、YAML 与 diff 检查通过。

## 进行中

- 无。

## 待完成

- GitHub CI 在 Docker 可用 runner 上执行 MySQL/PostgreSQL 真库步骤（外部 CI 状态，不阻塞本地 M6 完成）。

## 阻塞项

- 本机 Docker 不可用，显式真库测试按 assumption 跳过；workflow 已纳入该质量门禁。
