# M6 QA Report

## 结论

- 本地 MVP/CI-ready：通过。
- 生产真库：条件通过，等待有 Docker 的 CI 执行 MySQL 8/PostgreSQL Testcontainers。

## 环境与汇总

- Java：21.0.11
- 构建：仓库 Maven wrapper
- 默认集成库：H2 2.2（MySQL mode）+ Flyway V1-V3
- 最终 Surefire：55 suites，201 tests，0 failures，0 errors，0 skipped
- 构建产物：6 个模块 JAR

## 验收矩阵

| AC | 结果 | 证据 |
|---|---|---|
| AC-01 CSV 外圈/流式/架构边界 | pass | `recon-source-csv` ArchitectureTest、CsvSourceAdapterTest |
| AC-02 BOM/charset/delimiter/header/quotes/multiline | pass | UTF-8 BOM、UTF-16LE BOM、分号、引号逗号、跨行字段专项测试 |
| AC-03 reject/行号/继续处理 | pass | 语义坏行继续、不可恢复语法终止文件、Batch reject 落库断言 |
| AC-04 DB/CSV 路由与 DB 兼容 | pass | RoutingSourceAdapterTest + 全部既有 DB Job 回归 |
| AC-05 CSV 三方全链路 | pass | `MarketingThreeWayCsvEndToEndTest`，Job COMPLETED、raw_ref、处理链、报表 |
| AC-06 重跑/人工处置/建议保护 | pass | 同 Run attempt 2 后条数幂等、RESOLVED/operator 保持、建议不重复 |
| AC-07 九类差异 | pass | 单次 CSV 全链路复现九类，BRIDGE_BROKEN 同时覆盖 SEG1/SEG2 |
| AC-08 ArchUnit/全量/打包 | pass | 201 tests + 6 模块 clean package + diff check |
| AC-09 真库 CI/文档 | conditional-pass | workflow 已加显式命令；本地因 Docker 不可用跳过 2 条真库用例 |

## 命令证据

| 命令/检查 | 结果 |
|---|---|
| `./mvnw -q -pl recon-source-csv -am test` | pass |
| CSV 路由 + 全链路目标测试 | pass |
| `./mvnw -q test` | pass |
| `./mvnw -q clean package` | pass |
| 显式 `CollationRealDbIT` 命令 | exit 0；本地 Docker unavailable，2 tests skipped by assumption |
| Surefire XML 汇总 | 201/201 pass，0 skipped（默认 clean package 不含 `*IT`） |
| workflow YAML parse | pass |
| `git diff --check` | pass |

## 构建产物

- `recon-core/target/recon-core-0.0.1-SNAPSHOT.jar`
- `recon-source-db/target/recon-source-db-0.0.1-SNAPSHOT.jar`
- `recon-source-csv/target/recon-source-csv-0.0.1-SNAPSHOT.jar`
- `recon-scenario/target/recon-scenario-0.0.1-SNAPSHOT.jar`
- `recon-handler/target/recon-handler-0.0.1-SNAPSHOT.jar`
- `recon-batch/target/recon-batch-0.0.1-SNAPSHOT.jar`
