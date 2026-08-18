# M6 Delivery Report

## 结果

M6 已完成：CSV 数据源能够接入营销三方两段 Batch 全链路，设计要求的九类 MVP 差异均可执行复现，重跑、人工核销保护、冲正建议、reject 幂等和逐币种守恒均通过 H2 集成验证。

## 交付内容

- 新增 `recon-source-csv`，提供 BOM/编码识别、字符集冲突保护、可配置分隔符、CSV 引号/跨行字段、ISO 时间和 signed-long 金额标准化。
- 文件采用绝对路径 + 物理行号 raw_ref；语义坏行写 reject 后继续，语法/编码失去边界时记录 reject 后安全停止文件。
- 新增 `RoutingSourceAdapter`，默认 DB 行为不变；营销三方场景可通过环境变量切换三份 CSV。
- 场景模块新增格式无关 `SourceConfig`，账务 spine 可在 SEG1/SEG2 复用同一文件并投影不同键。
- 修复重跑未清 reject 的幂等缺口，并保留 disposition/reversal/action 人工与审计表。
- 新增 CSV 单元、架构、路由、三方全链路、九类差异和重跑保护测试。
- README、CLAUDE、Known Issues、application.yml 与 GitHub CI 已同步。

## 验收摘要

| AC | 状态 |
|---|---|
| AC-01～AC-08 | pass |
| AC-09 | conditional-pass：本地无 Docker，CI 已配置真库执行 |

## 发布说明

- 默认 `recon.m4.source-type=db`，因此升级不会自动切到 CSV。
- 启用 CSV 前按 README 配置三条文件路径、字符集和分隔符，并保证输入文件是不可变快照。
- CI 的 MySQL/PostgreSQL 步骤绿后，才可把真库方言项视为正式通过。
- Git 提交与推送已由用户在交付完成后单独授权；未部署，也未连接生产系统。

## 下一阶段

MVP M0-M6 已闭环。后续需另行定范围，可优先选择：生产容量/预校验加固，或阶段二的配置驱动场景、Drools 和 Flowable。
