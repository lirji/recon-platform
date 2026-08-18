# 已知问题 / 局限（Known Issues）

> 记录当前阶段（M0–M3）经对抗式 review 确认、但**有意暂不修**或**降级为窄边角**的已知问题，避免"看似干净"的误导。每条注明触发条件、影响、缓解与后续计划。均为 low 级或 off-by-default 场景，不阻塞 MVP。

## KI-1 · sub-bucket 二级分桶 + restart 中途改配置 → 局部结果静默错算

- **触发**：`recon.skew.sub-bucket.enabled=true`（**默认关**）时，某次 Run 部分 partition 完成后失败；运维在**同一 JobInstance restart 前修改了 skew 配置**——① 改 `fanout` 数值（如 8→4），或 ② 连续多次翻转整桶↔sub-bucket 形状（sub→whole→sub）。
- **影响**：`prepareRunStep`（含 `cleanBounded` 清 `recon_report_partial`）在 restart 时被 Spring Batch 跳过（已 COMPLETED），新旧分片形状/fanout 的局部结果混存，`ConservationMerger` 重复累计或漏算 → 报表金额错，但左右口径同比例膨胀使 `residual` 仍 ≡0，**骗过守恒门禁**、Run 被标 `COMPLETED/balanced`。
- **已缓解到**：整桶↔sub-bucket 的**单次形状翻转**已由 `MatchEvaluateWriter` 的 worker 级 stale-partial 清理修复（保留 partition-resume 语义）；上述残留仅剩 **fanout 数值变**与**多次连续翻转**两个更窄子情形。
- **规避（运维约束）**：**failed Run 的 restart 前不要修改 `recon.skew.*` 配置**；sub-bucket 默认关，绝大多数部署不受影响。
- **根治计划（M5/加固）**：把 skew 配置指纹（enabled+fanout）随 Run 持久化，restart 时若配置变则 **fail-fast** 拒绝（而非静默错算）；或把 shape/epoch 编入 partial 主键 + restart 做 run 级 partial 全清。

## KI-2 · per-bucket 游标为可移植 null 序牺牲了纯索引产序（轻微 filesort）

- **背景**：遗留②（M3）本让 per-bucket 游标 `ORDER BY match_key` 走 `idx_merge` 免 filesort；但为解决 M4 spine 缺记录侧产生的 null match_key 跨方言排序（MySQL NULLS FIRST vs PG LAST），#8 修复改用可移植的 `ORDER BY (match_key IS NULL), match_key`。
- **影响**：前导 `(match_key IS NULL)` 表达式使 MySQL 无法纯索引产序，per-bucket 游标回到**轻微 filesort**。单桶行数有界，代价可控；**正确性（M4 null 键 + 跨方言）优先于免 filesort**。
- **后续优化**：join 游标改 `WHERE match_key IS NOT NULL ORDER BY match_key`（索引有序）+ null 键单独查询路由，可同时拿到索引有序与 null 正确性。

## KI-3 · sub-bucket 兜底：每子分区重扫整个热桶（IO×fanout）

- **触发**：`sub-bucket.enabled=true`（默认关）。每个子分区的 reader 仍 `cursor(...,bucket)` 读**整个热桶**再内存按 `subIndex` 过滤（`Bucketing.subIndexOf` 用 Java hash+avalanche，无可移植 SQL 等价可下推）。
- **影响**：热桶被扫描 `fanout` 遍，用 IO 放大换判差 CPU 并行度——**仅当热桶瓶颈在判差 CPU 而非 IO 时划算**。已在 `application.yml`、`BucketPartitioner` 告警日志、`BucketGroupReader` javadoc 如实标注，不作"已缓解"的误导。默认关，显式 opt-in。

## KI-4 · 真实 MySQL/PG 端到端仅在有 Docker 时验证

- 集成测试默认 H2（免 Docker）。collation 真库测试 `CollationRealDbIT`（Testcontainers MySQL8 + PG）在**无 Docker 时优雅跳过**（`DockerClientFactory.isDockerAvailable()` 守卫），`./mvnw test` 保持绿但那 2 条不真跑。
- V3 collation ALTER + 索引重建、`fetchSize=Integer.MIN_VALUE` 真流式等**真库行为**建议在有 Docker/真实 MySQL8+PG 的环境跑一遍 `CollationRealDbIT` 确认。

## KI-5 · REPORT_IMBALANCE 为构造性护栏（正常路径不可达）

- 设计 §8 双向守恒是**构造性恒等**（每条记录恰落一类，residual by-construction ≡0）。`REPORT_IMBALANCE` 分支只在"桶路由被改坏 / 溢出回归"时触发，正常数据不可达。已由 `ReportTaskletImbalanceTest`（注入 residual≠0）覆盖该终态分支，证明门禁真能 fire——它是**回归护栏**，不是常规路径。
