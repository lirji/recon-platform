# 已知问题 / 局限（Known Issues）

> 记录当前阶段（M0–M6）经对抗式 review 确认、但**有意暂不修**或**降级为窄边角**的已知问题，避免"看似干净"的误导。每条注明触发条件、影响、缓解与后续计划。均为 low 级或 off-by-default 场景，不阻塞本地 MVP 验收。

## KI-1 · sub-bucket 二级分桶 + restart 中途改配置 → 局部结果静默错算

- **触发**：`recon.skew.sub-bucket.enabled=true`（**默认关**）时，某次 Run 部分 partition 完成后失败；运维在**同一 JobInstance restart 前修改了 skew 配置**——① 改 `fanout` 数值（如 8→4），或 ② 连续多次翻转整桶↔sub-bucket 形状（sub→whole→sub）。
- **影响**：`prepareRunStep`（含 `cleanBounded` 清 `recon_report_partial`）在 restart 时被 Spring Batch 跳过（已 COMPLETED），新旧分片形状/fanout 的局部结果混存，`ConservationMerger` 重复累计或漏算 → 报表金额错，但左右口径同比例膨胀使 `residual` 仍 ≡0，**骗过守恒门禁**、Run 被标 `COMPLETED/balanced`。
- **已缓解到**：整桶↔sub-bucket 的**单次形状翻转**已由 `MatchEvaluateWriter` 的 worker 级 stale-partial 清理修复（保留 partition-resume 语义）；上述残留仅剩 **fanout 数值变**与**多次连续翻转**两个更窄子情形。
- **规避（运维约束）**：**failed Run 的 restart 前不要修改 `recon.skew.*` 配置**；sub-bucket 默认关，绝大多数部署不受影响。
- **已加固（A5, 2026-08-19）**：`SkewConfigGuardListener`（挂两个 Job）把 skew 形状指纹 `enabled|fanout` 写入 **Job 级 ExecutionContext**（跨 restart 由 Spring Batch 复制持久化），`beforeJob` 比对上次形状,对 KI-1 明列的两个残留 **fail-fast**——① **fanout 数值变**（两次均 enabled 但 fanout 不同）；② **累计形状翻转 ≥2**（多次连续翻转）。**单次**整桶↔sub 翻转（fanout 不变）仍放行（已由 worker 级 stale-partial 清理覆盖,见 `ReconJobShapeFlipRestartTest`）。回归由 `ReconJobSkewFanoutRestartGuardTest` 守。sub-bucket 默认关时守卫恒 no-op。
- **残留**：运维仍应遵守「restart 前不改 `recon.skew.*`」作纵深防御;守卫是把静默错算升级为显式 fail-fast,不改变「不建议 restart 前改配置」的运维约束。

## KI-2 · per-bucket 游标为可移植 null 序牺牲了纯索引产序（轻微 filesort）

- **背景**：遗留②（M3）本让 per-bucket 游标 `ORDER BY match_key` 走 `idx_merge` 免 filesort；但为解决 M4 spine 缺记录侧产生的 null match_key 跨方言排序（MySQL NULLS FIRST vs PG LAST），#8 修复改用可移植的 `ORDER BY (match_key IS NULL), match_key`。
- **影响**：前导 `(match_key IS NULL)` 表达式使 MySQL 无法纯索引产序，per-bucket 游标回到**轻微 filesort**。单桶行数有界，代价可控；**正确性（M4 null 键 + 跨方言）优先于免 filesort**。
- **后续优化**：join 游标改 `WHERE match_key IS NOT NULL ORDER BY match_key`（索引有序）+ null 键单独查询路由，可同时拿到索引有序与 null 正确性。

## KI-3 · sub-bucket 兜底：每子分区重扫整个热桶（IO×fanout）

- **触发**：`sub-bucket.enabled=true`（默认关）。每个子分区的 reader 仍 `cursor(...,bucket)` 读**整个热桶**再内存按 `subIndex` 过滤（`Bucketing.subIndexOf` 用 Java hash+avalanche，无可移植 SQL 等价可下推）。
- **影响**：热桶被扫描 `fanout` 遍，用 IO 放大换判差 CPU 并行度——**仅当热桶瓶颈在判差 CPU 而非 IO 时划算**。已在 `application.yml`、`BucketPartitioner` 告警日志、`BucketGroupReader` javadoc 如实标注，不作"已缓解"的误导。默认关，显式 opt-in。

## KI-4 · 真实 MySQL/PG 端到端仅在有 Docker/真库时验证（A3 已收口）

- 集成测试默认 H2（免 Docker），`./mvnw test` 不自动包含 `*IT`。真库验证有**两条互补路径**：
  - **`CollationRealDbIT`（Testcontainers MySQL8 + PG）**：自动起容器，**无 Docker 时优雅跳过**（`DockerClientFactory.isDockerAvailable()` 守卫）。⚠️ Testcontainers 1.19.8 内置的 docker-java 与**过新的 Docker Engine（API ≥1.55，如 Engine 29 / Desktop 4.87）不兼容**——`/info` 返回 400 使 `isDockerAvailable()` 判否而跳过（非代码缺陷）。CI 用受控 Docker 版本时正常起容器。
  - **`RealDbEndToEndIT`（系统属性驱动，直连外部库）**：不依赖 Testcontainers 自动发现，直连 `docker run` / compose 起的真库，绕开上面的版本不兼容。覆盖面比 collation IT 更广：**生产同款 Flyway（V1 领域 + V2 方言 batch 元数据 + V3 collation）** 全量迁移 + **方言 batch 序列**（MySQL 表式 `BATCH_JOB_SEQ` / PG `CREATE SEQUENCE`）+ collation 序/PAD SPACE + `idx_merge` 计划 + **`fetchSize=Integer.MIN_VALUE` 真流式游标**（经生产 `JdbcReconRecordStore.cursor`）。运行方式见 `README.md`「生产 DB」小节。
- **实测记录（A3, 2026-08-19）**：`RealDbEndToEndIT` 对真实 **MySQL 8.0.46 + PostgreSQL 16.15** 两方言全绿（`docker run` 起库，非 Testcontainers）。顺带发现并修复 `perBucketPlan` 的方言 `ANALYZE` 语法坑（MySQL 需 `ANALYZE TABLE <t>`，PG 用 `ANALYZE <t>`）——此前 Testcontainers 从未真跑过故未暴露。

## KI-5 · REPORT_IMBALANCE 为构造性护栏（正常路径不可达）

- 设计 §8 双向守恒是**构造性恒等**（每条记录恰落一类，residual by-construction ≡0）。`REPORT_IMBALANCE` 分支只在"桶路由被改坏 / 溢出回归"时触发，正常数据不可达。已由 `ReportTaskletImbalanceTest`（注入 residual≠0）覆盖该终态分支，证明门禁真能 fire——它是**回归护栏**，不是常规路径。

## KI-6 · match_key→group_key 数据函数性靠上游保证（脏跨表数据产假 BRIDGE_BROKEN/EXTRA，守恒抓不到）

- **背景**：放宽 refine（M4）后，`StandardizeProcessor` 生产热路径只调 O(1) 的结构性 `Bucketing.assertRefine`（带 `match_key` 必须有非空 `group_key` 以分桶）。**函数性** refine——"同一 `match_key` 跨两侧只映射到唯一 `group_key`"（`Bucketing.assertRefineFunction`）——需跨记录全表 `match→group` 映射，千万级热路径**不能**逐条建表，故只在单测/离线抽样调用。`SpineBridgeKeyExtractor` 的装配期校验也仅覆盖键**字段名**接线（声明非空 + 落库字段名一致），**不**校验数据值。
- **触发**：脏跨表数据违反函数性——同一 `match_key`（如同一营销发放ID）在左侧记录挂 `group_key=Ga`、右侧记录挂 `group_key=Gb`（`Ga != Gb`）。
- **影响**：两侧 `bucket = floorMod(hash(group_key), N)` 落**不同桶** → sort-merge join 只在单桶内跑 → 两侧永不相遇 → 左侧成 `LEFT_ONLY`（`MISSING`/`BRIDGE_BROKEN`）、右侧成 `RIGHT_ONLY`（`EXTRA`/`BRIDGE_BROKEN`），即**假 BRIDGE_BROKEN + 假 EXTRA**。因左右额分别独立入各自口径，双向守恒仍 `residual≡0` **balanced**，**门禁抓不到**（守恒只证桶路由/溢出，不证分类判定，见 §8 口径澄清）。
- **规避（数据质量约束）**：上游保证同一 `match_key` 只属唯一 `group_key`（营销发放ID→唯一发放单号）；MVP 两段的真实数据满足此约束。可选 **opt-in 离线/装配期抽样预校验**：`Bucketing.assertRefineFunction(matchKey, groupKey, witnessed)` 逐条累计 `match→group` 映射，同键映射到不同 group 即 fail-fast——因需跨记录 `witnessed` 状态，**只**用于抽样/有界校验或单测，**不**进千万级热路径。
- **已加固（A5, 2026-08-19）**：只读诊断端点 `GET /recon/runs/{id}/refine-violations`（`ReconConsoleQueryService.refineViolations` → `JdbcReconConsoleQueryStore.findRefineViolations`）。DB 侧 `GROUP BY segment_id, match_key HAVING COUNT(DISTINCT group_key) > 1` 扫 staged `recon_record`,列出同一 (segment, match_key) 映射多个 group_key 的违规（null 键排除,按冲突组数降序,有界 100 且超出置 `truncated`）。**不建 Java 全表映射、不占对账热路径**,把「守恒抓不到的假 BRIDGE_BROKEN/EXTRA」升级为**显式可发现**。回归由 `RefineViolationsTest` 守。
- **残留**：仍是**事后诊断**（对已 load 的 staging 扫描）,非 join 阶段实时拦截;上游数据质量约束（同一 match_key 唯一 group_key）仍是第一道防线。源表级预扫（load 前拦）可后续按需再加。

## KI-7 · CSV 语法恢复、reject 峰值与行号身份边界

- **不可恢复语法/编码错误**：字段语义错误可逐条 reject 后继续；但未闭合引号、截断文件或非法字节会让流式解析器失去可靠记录边界。适配器会记录当前物理行 reject 并停止该文件，避免猜测边界后错位入账。上游应先做文件完整性/校验和检查。
- **reject 峰值内存**：现有 `RecordCursor` SPI 以列表返回 reject；极端全坏文件会在单侧游标关闭前累计 reject 元数据。正常文件不 materialize 记录，仍是流式；大批坏行应通过上游质量门禁拦截，后续可把 SPI 改为 reject sink/流式回调。
- **行号身份漂移**：null match_key 的 fingerprint 以 `raw_ref=file:line` 鉴别。同一文件若在原行之前插行，重跑时该类记录 fingerprint 会变化，旧人工处置按 A1 进入 STALE 而非 re-link。生产应把输入文件视为不可变快照；若业务有稳定源主键，后续可把它加入文件血缘身份。
