# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 通用自动对账系统。**架构与字段级设计的权威来源是 `docs/design/RECON_MVP_DESIGN.md`**（judge-panel 综合定稿：领域模型、DDL、四接口签名、桥接两段匹配时序、ADR、口径决议 A0–A8）。`README.md` 含可复制的构建/测试命令。改动前先读设计定稿，缺失需求不臆造——标为假设或待澄清。

## 技术基线

Java 21 / Spring Boot 3.3.5 / Maven 多模块（`com.lrj.recon:recon-platform`，`0.0.1-SNAPSHOT`，pom 打包）。ArchUnit 1.3.0 作持续门禁。构建用仓库自带的 `./mvnw`（wrapper），**勿用系统 mvn**。集成测试用 H2 内存库（MySQL 兼容模式）+ Flyway，免 Docker 可跑绿。

## 构建与测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # 本机(macOS)跑 mvn 前必做

./mvnw -q test                        # 全量编译 + 测试(含 ArchUnit 门禁 + H2 集成测试)
./mvnw -q -pl recon-core test         # 只跑领域内核
./mvnw -pl recon-core test -Dtest=ClassName            # 单个测试类
./mvnw -pl recon-core test -Dtest=ClassName#methodName # 单个方法
./mvnw -q clean package               # 全量打包
```

`recon-batch` 是唯一的 Spring Boot 组合根；对账作业是 Spring Batch Job `reconciliationJob`，`spring.batch.job.enabled=false`（不随 Boot 启动自动跑，由调度/测试显式 launch）。

## 模块与依赖方向（依赖箭头一律指向 `recon-core`）

- **`recon-core`**（纯 Java 零框架）：领域模型 + `spi` 四接口 + `application.port.out` 7 端口 + 领域服务（`SortMergeJoiner` / `GroupSumMatchStrategy` / `DiscrepancyClassifier` / `ExactEvaluator` / `EvaluatorFactory` / `ConservationChecker` / `MoneyMath` / `Fingerprint` / `Bucketing`）。
- **`recon-source-db`**：`SourceAdapter` 的 DB 实现（`DbSourceAdapter` + keyset 前向游标）。
- **`recon-batch`**（组合根）：Spring Batch 编排 + 7 个 `Jdbc*Store`（实现 core 端口）+ Flyway 迁移 + 调度。

**ArchUnit 门禁（每模块各一份 `ArchitectureTest`，改动别踩）**：`recon-core` 的 `..domain..`/`..spi..`/`..application..` 禁依赖 `org.springframework..`、`org.springframework.batch..`、`org.kie..`、`java.sql..`、CSV、`org.flowable..`、JPA、`..adapter..`；额外一条**金额路径禁 `double`/`Double`**。Spring Batch/JDBC 只允许落在 `recon-batch` 的 `job`/`config`/`persistence` 包。接新数据源/规则只写外圈实现，别把框架依赖漏进 core。

## 架构要点（跨文件才看清的部分，改动前务必理解）

**责任链两段 + spine 桥接**：营销三方对账 = `SEG1 营销↔账务`（join 营销发放ID）+ `SEG2 账务↔渠道`（join 渠道流水号），账务侧是 spine 同时持双键。账务缺记录 → `BRIDGE_BROKEN` 且置 `bridge_break_stage`（SEG1/SEG2），**优先级高于 MISSING、不叠加**。真正的 `SpineBridgeKeyExtractor` + 三方场景装配在 M4；M2/M3 用占位 `IdentityKeyExtractor`（match_key==group_key）跑单段。

**金额与守恒**：全链路 `signed_amount_minor`（`long` 分，红蓝字），`Money` 封装、禁 double、跨币种 `compare` 抛 `CurrencyMismatchException`，`MoneyMath.addExact` 溢出 fail-fast。守恒（`ConservationChecker`，设计 §8）是**构造性双向、双 matched 口径**：`matchedLeft`=干净匹配左额（即报表 `matched_amount_minor`）；`matchedRight`=干净匹配右额 + 所有"两侧配上但有差"组的右额（右侧看它们"配上了只是有差"）。按币种分桶、跨币不相加，`left_residual`/`right_residual` 均 by-construction ≡ 0。**⚠️ residual≡0 只抓"桶路由改坏 / 溢出"，不证明 `DiscrepancyClassifier` 判对**——分类正确性靠各差异桶数值断言 + 分类器单测，别把守恒当分类正确性的证明。

**保护人工核销（最大产品风险，ADR-7）**：机器判差（`discrepancy`）/ 人工处置（`discrepancy_disposition`）/ 冲正建议（`reversal_suggestion`）**三表分离**。重跑只分批清 `recon_record` + `machine_result=1` 的 `discrepancy`（`ReconRerunService.cleanBounded`，每批独立事务防大事务锁全表），**绝不触碰** disposition/reversal——`JdbcDiscrepancyDispositionStore` 结构上就没有 delete 方法。差异身份用 `fingerprint`（SHA-256 canonical，null 键 → `'∅'`），`uk_disc(run_id, fingerprint)` 让空键类型也幂等；`upsertByFingerprint` 走可移植 update-else-insert + 并发 DuplicateKey 回退。冲正 MVP 只生成建议（`SUGGESTED`），无资金动作。

**分桶与勾兑（易踩坑）**：`bucket = floorMod(hash(group_key), N)`（桶键=group_key，`Bucketing`）。**不变式：match_key 必须是 group_key 的细分**（同一 match_key 只属唯一 group_key）——M2/M3 为 `match_key==group_key`（IDENTITY 特例），**M4 放宽为一般 refine**（如 SEG1 营销发放ID→发放单号 1:N）。生产装载期唯一 refine 关卡是 `StandardizeProcessor` 调 **`Bucketing.assertRefine`**（O(1) 结构性，允许 match≠group）；`assertIdentityRefine`/`assertRefineFunction` 是特例/函数性校验，**main 代码不接线**（仅单测/离线抽样）。⚠️ **函数性 refine（同 match_key 跨两侧→同 group_key）生产热路径不逐条校验**（千万级不建全表映射），脏跨表数据违反会产假 BRIDGE_BROKEN/EXTRA 且守恒抓不到——见 `docs/KNOWN_ISSUES.md` KI-6。`match_key` 可空且是勾兑键：游标排序用**可移植 `ORDER BY (match_key IS NULL), match_key`**（消除 MySQL NULLS-first vs PG NULLS-last），**null 键记录逐条路由为单边组、绝不进 `SortMergeJoiner`**（joiner 拒 null 键会抛异常）；同 group_key 下多条 null-key 差异靠 rawRef 鉴别 fingerprint 防碰撞（否则台账 undercount）。

**大数据量（千万级）红线**：不全量 load 内存——`MatchGroup` 只持流式聚合（sum/count/presence），不持全记录列表；排序落 DB（per-bucket 游标 `ORDER BY match_key` 走 `idx_merge`）。MySQL 真流式游标须 `fetchSize=Integer.MIN_VALUE` + forward-only（`JdbcReconRecordStore` 按 `DatabaseProductName` 判定；H2 测试查不出这条）。

**方言差异**：领域 DDL（`V1__recon_schema.sql`）MySQL8/PG/H2 通用。Spring Batch 元数据按方言拆在 `db/batch/{h2,mysql,postgresql}`——**MySQL 用官方表式序列 `BATCH_*_SEQ`（不支持 `CREATE SEQUENCE`）**，H2/PG 用 `CREATE SEQUENCE`。

**Drools 预留**：`DiscrepancyEvaluator` 阶段二才上 Drools；`DroolsEvaluator` 仅留接口，`EvaluatorFactory` 遇 `DROOLS`/未实现类型**抛 `UnsupportedOperationException` fail-fast，绝不静默跳过判差**。

## 当前进度（walking-skeleton）

M0 领域内核 ✅ / M1 持久化 ✅ / M2 Spring Batch 单线程 ✅ / **M3 分桶并行 🚧** / M4 两段桥接 / M5 处理链+人工核销+outbox / M6 CSV+加固。每个里程碑收官时 `./mvnw -q test` 必须全绿且各模块 ArchUnit 门禁通过。
