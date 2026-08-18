# 通用自动对账系统 · 阶段一 MVP · 最终设计定稿(综合版)

> 本稿以最高分方案「贴合现有架构」(8.5) 为主线,嫁接「健壮优先」的 fingerprint / MoneyMath / 构造性双向守恒,与「最简可用」的 bridge_break_stage / discrepancy_action / group-reader,并逐条补掉主线被评委点出的 8 个缺口。停在设计定稿,可直接喂 `java-build-review`。

---

## 0. 综合决策:主线 + 嫁接 + 修补

| 来源 | 采纳内容 |
|---|---|
| 主线(贴合现有架构) | 六边形 + recon-core 纯度 + ArchUnit 门禁;JdbcTemplate + Flyway expand-contract;revision 乐观锁;7 模块;责任链两段 + spine 桥接;**三表分离**(机器判差 / 人工处置 / 冲正建议);DB ORDER BY sort-merge 落盘排序;Spring Batch partitioned |
| 嫁接·健壮优先 | **fingerprint 稳定指纹**(替换空键自然键)、**MoneyMath.addExact 溢出 fail-fast**、**构造性守恒**(每记录恰落一类)、**左/右双向守恒**、**MatchGroup 只持流式聚合而非全列表** |
| 嫁接·最简可用 | **bridge_break_stage(SEG1/SEG2)** 断链精确归因、**discrepancy_action 审计+幂等表**、**group-reader**(整组=一 item 防 chunk 切组)、人工核销 **version 乐观锁**、CHECK(currency=3) |
| 新增修补(主线 8 缺口) | ①桶键=group_key 对齐不变式 ②fingerprint 解空键幂等 ③分批删除替代大事务 ④签名守恒算术钉死 ⑤告警走 outbox 出 chunk 事务 ⑥walking-skeleton 递进 ⑦seq 分配串行化 + 重跑语义 ⑧TIMING claimed_run_id |

---

## 0.1 与 legacy / v2 的边界(必须先澄清的伪前提)

**结论:本项目是 greenfield,不存在 legacy 与 v2 两代结构。** 三份方案一致核实:工作区无任何既有 recon / mall / MyBatis-Plus / legacy-v2 代码可溯源。任务描述中"复用 legacy/v2"实为**借鉴** `/Users/liruijun/personal/LLM/risk-platform` 的工程模板与三处成熟范式(六边形分层、ArchUnit 内核门禁、JDBC 唯一键+revision 幂等)。

- 新建 `/Users/liruijun/personal/LLM/recon-platform`,parent 继承 spring-boot-starter-parent 3.3.5,Java21,`./mvnw`,groupId `com.lrj.recon`。
- **与既有系统零代码耦合**:仅在依赖/规范层面对齐 risk-platform;不 import 任何 risk-platform / fraud-engine 代码。
- **[待澄清 A0]** 幂等范式的具名参考类冲突:主线评委称 `JdbcIngestionJobStore` / `IngestionReconciler` 存在并被准确引用;另一评委称真实类为 `JdbcDeadEventAdapter` / `HisWorkflowStore` / `ProcessLinkRepository`。落地前需 `ls` 确认具名类;**范式本身(唯一键 + DuplicateKeyException 吞 + `save(entity, expectedRevision)` 条件更新失败抛 Conflict)确定存在**,设计只依赖范式不依赖具名类。

---

## 1. Goals / Non-goals

**Goals**
- 稳定七段流水线【拉取→标准化→勾兑匹配→差异判定→分类→处理→报表复核】+ 四可插拔接口;recon-core 纯 Java 零框架(ArchUnit 门禁)。
- 营销发钱三方对账 = 责任链两段(SEG1 营销↔账务 join 营销发放ID、SEG2 账务↔渠道 join 渠道流水号),账务侧 spine 桥接双键;**各段独立守恒、独立产差**。
- 发现并正确分类:AMOUNT_MISMATCH / MISSING / DUPLICATE / EXTRA / TIMING / STATUS_MISMATCH / CURRENCY_MISMATCH / GROUP_SUM_MISMATCH / BRIDGE_BROKEN(FX_RATE_DIFF 仅留字段不判)。
- 金额全链路 `long`(分,signed)+ currency;跨币种不换算、不可直接比,币种不一致直接 CURRENCY_MISMATCH。
- 退款红蓝字 entry_type(ISSUE/REFUND/REVERSAL)、signed_amount_minor;发放单级 GROUP_SUM_MISMATCH。
- 账期 + cutoff + T~T+1 窗口,跨日错位判 TIMING 而非 MISSING。
- Run 唯一键(scenario+账期+序号)幂等可重跑;人工核销闭环;冲正只生成建议(幂等键、无资金动作);raw_ref 血缘;ReconReport 守恒自证。
- 百万~千万级:按 group_key hash 分桶并行 + 桶内 sort-merge join,排序落 staging DB(非全内存)。

**Non-goals(字段/接口留位,算法阶段二)**
- DSL 规则平台;Flink/Kafka 流式;跨币种换算 + 汇率容差 + FX_RATE_DIFF 算法(fx_rate/base_amount_minor 只读存档不参与比较);1:N 明细级下钻(只到发放单级总额);SEG2 发放单跨渠道流水号 roll-up;自动冲正执行;DroolsEvaluator 实现;Flowable 工单落地;三方合并视图(仅可选只读 roll-up 摘要,标待澄清)。

---

## 2. 模块划分与依赖方向(依赖箭头一律指向 recon-core)

```
recon-core (纯 Java, 仅 JDK)
  ├─ domain.model      领域模型 + 值对象 + 枚举
  ├─ domain.service    SortMergeJoiner / DiscrepancyClassifier / ConservationChecker
  │                    / MoneyMath / Fingerprint / ReconRunStateMachine / DiscrepancyStateMachine
  ├─ spi               SourceAdapter / KeyExtractor / MatchStrategy
  │                    / DiscrepancyEvaluator / DiscrepancyHandler
  └─ application.port.out  6 个持久化端口
        ↑            ↑            ↑            ↑            ↑
 recon-source-db  recon-source-csv  recon-rule  recon-handler  recon-scenario   (外圈, 互不横向依赖, 各自只依赖 core)
        ↑____________↑____________↑____________↑____________↑
                        recon-batch (Spring Boot 组合根)
             Spring Batch 编排 + JDBC 适配器 + 调度 + REST + Flyway + alert-outbox 中继
```

- ArchUnit 门禁(照搬 fraud-engine `ArchitectureTest`,recon-core 各模块各一份):`..domain..` / `..spi..` / `..port..` 禁依赖 `org.springframework..`、`org.springframework.batch..`、`org.kie..`、`java.sql..`、`com.opencsv..` / `org.apache.commons.csv..`、`org.flowable..`、`jakarta.persistence..`、`..adapter..`;额外一条:**金额路径禁 double/Double**(ReconRecord/Money/Discrepancy 字段类型断言)。
- **MVP 不单拆 recon-contracts**(最简可用取舍),对外 DTO/枚举先并入 core;阶段二对外发版契约再抽(参照 platform-contracts)。标为假设。
- recon-source-db 与 recon-source-csv **保持两模块**(主线),因两者依赖完全不同(JdbcTemplate vs 文件流),便于 ArchUnit 独立门禁。

---

## 3. 领域模型(recon-core,职责与关系)

- **ReconScenario**(配置根):scenarioCode + 有序 `List<SegmentSpec>`。营销三方 = [SEG1_MKT_ACCT, SEG2_ACCT_CHANNEL]。
- **SegmentSpec**:segmentId、SourceRole leftRole/rightRole、spineRole、extractorId、strategyId、evaluatorId、`List<String>` handlerIds。
- **ReconRun**(执行聚合根):runId、RunKey(scenarioCode, accountingPeriod, sequenceNo)、cutoffTime、matchWindowFrom(T)/matchWindowTo(T+1)、bucketCount、ReconRunStatus、`long revision`(乐观锁)。方法 `start()/toMatching()/complete()/markImbalance(reason)/fail()`,非法流转抛 IllegalState。
- **ReconRecord**(标准化 VO,不可变 builder):recordId、runId、segmentId、Side、SourceRole、matchKey、`int bucket`、groupKey、`Money money`、baseAmountMinor/fxRate/fxRateTime/fxRateSource(只读存档)、EntryType、bizStatus、bizTime、postingTime、rawRef、claimedRunId(TIMING 用)。
- **Money**(值对象):`String currency` + `long amountMinor`。`add(Money)`(同币种,`MoneyMath.addExact`);`compareSameCurrency(Money)`——跨币种抛 `CurrencyMismatchException`;**禁 double 构造**。
- **MatchKey / GroupKey**:VO,封装键值 + 字段名 + bucket;equals/compareTo 供 sort-merge。
- **MatchGroup**(1:N 聚合单元,**只持流式聚合,不持全列表**——修补内存缺口):groupKey、matchKey、`long sumSignedLeftMinor / sumSignedRightMinor`、`int countLeft / countRight`、presence(BOTH/LEFT_ONLY/RIGHT_ONLY)、duplicate 标记、`currency`、有界 rawRef 样本(左右各首条,供血缘,不物化全组)。阶段二明细下钻才物化列表。
- **DiscrepancyRule**:absToleranceMinor、ratioToleranceBps、timingWindow、`Set<DiscrepancyType> enabled`、evaluatorType{EXACT,TOLERANCE,DROOLS}——同时充当阶段二 Drools fact。
- **Discrepancy**(机器判差结果,**不含人工状态**):discrepancyId、runId、segmentId、DiscrepancyType、`bridgeBreakStage`(SEG1/SEG2)、groupKey、matchKey、currency、expected/actual/delta Minor、leftRecordRef/rightRecordRef、machineResult=1、**`fingerprint`**、timestamps。人工状态在独立表。
- **DiscrepancyType**(10 值)+ `precedence()`(见 §9)。
- **DiscrepancyDisposition**(人工处置,独立于 Run 生命周期):按 fingerprint,action(RESOLVE/CLOSE/SUPPRESS/REOPEN)、operator、note、`version` 乐观锁。
- **ReversalSuggestion**(冲正建议,幂等):按 idempotencyKey,status(SUGGESTED/CONFIRMED/DISCARDED),无资金动作。
- **ReconReport**(勾稽):按 (segmentId, currency) 桶,expected/matched/per-type Minor、右口径 extra + right-bridge、residual、balanced。

---

## 4. 四插件接口 Java 签名 + MVP 实现清单(recon-core `com.lrj.recon.core.spi`)

```java
// 1) 数据源:拉取 + 标准化
public interface SourceAdapter {
    String sourceId();                                     // "db" | "csv-file"
    boolean supports(SourceDescriptor descriptor);
    RecordCursor open(SourceReadContext context);          // 惰性前向游标, 禁全量 load
}
public interface RecordCursor extends AutoCloseable {
    ReconRecord next();                                    // 无更多返回 null
    @Override void close();
    default List<RejectedRow> rejects() { return List.of(); } // 畸形行, 不中断整流
}

// 2a) 键抽取(桥接)
public interface KeyExtractor {
    String extractorId();
    MatchKey extract(ReconRecord record, SegmentSpec segment, int bucketCount); // 该侧无键→null
    GroupKey groupKey(ReconRecord record, SegmentSpec segment);                 // 1:N 聚合键
}

// 2b) 匹配策略:桶内 sort-merge + 1:N 聚合, 常量内存
public interface MatchStrategy {
    String strategyId();
    void join(MatchInput left, MatchInput right, MatchSink sink);  // 两路已按 matchKey 升序(DB 排序)
    interface MatchInput { boolean advance(); ReconRecord current(); MatchKey currentKey(); }
    interface MatchSink  { void emit(MatchGroup group); }
}

// 3) 判差:内置 Exact/Tolerance, 预留 Drools
public interface DiscrepancyEvaluator {
    String evaluatorId();
    List<Discrepancy> evaluate(MatchGroup group, DiscrepancyRule rule, EvaluationContext ctx); // 纯函数
}
public interface DroolsEvaluator extends DiscrepancyEvaluator { } // 仅接口, 误配 fail-fast

// 4) 处理:告警/台账/冲正建议
public interface DiscrepancyHandler {
    String handlerId();
    boolean supports(Discrepancy discrepancy);
    HandlerResult handle(Discrepancy discrepancy, HandlerContext ctx);  // 幂等键 = fingerprint+handlerId
    HandlerKind kind();  // TRANSACTIONAL | EXTERNAL_SIDE_EFFECT  ← 修补⑤: 区分事务性/外部副作用
}
```

**MVP 实现清单**
- SourceAdapter → `DbSourceAdapter`(keyset 游标分页,rawRef=表:主键) / `CsvSourceAdapter`(流式,BOM/编码,rawRef=文件:行号,畸形行入 reject 不中断)
- KeyExtractor → `SpineBridgeKeyExtractor`(SEG1 取 marketingIssueId,SEG2 取 channelSerialNo;spine 侧双键都取)
- MatchStrategy → `GroupSumMatchStrategy`(桶内 sort-merge 流式归并,组内 signed 求和,产 MatchGroup)
- DiscrepancyEvaluator → `ExactEvaluator` / `ToleranceEvaluator`(+ `EvaluatorFactory`,DROOLS→抛 `UnsupportedOperationException` fail-fast,绝不静默跳过判差);`DroolsEvaluator` 仅占位
- DiscrepancyHandler → `LedgerHandler`(TRANSACTIONAL,写 discrepancy 台账) / `ReversalSuggestionHandler`(TRANSACTIONAL,insertIfAbsent) / `AlertHandler`(**EXTERNAL_SIDE_EFFECT,只写 alert_outbox 不直接发**) / `ManualClearingService`(在线服务,非批处理);`FlowableTicketHandler` no-op 占位

---

## 5. 字段级 DDL(MySQL8/PG 通用;金额一律 BIGINT 分,禁 double)

```sql
-- 执行实例:唯一键 + cutoff + T~T+1 窗口 + revision 乐观锁
CREATE TABLE recon_run (
  run_id            VARCHAR(64) PRIMARY KEY,
  scenario_code     VARCHAR(64)  NOT NULL,
  accounting_period VARCHAR(16)  NOT NULL,          -- [待澄清 A5] 假设日账期 'YYYY-MM-DD'
  sequence_no       INT          NOT NULL,
  cutoff_time       TIMESTAMP    NOT NULL,
  match_window_from TIMESTAMP    NOT NULL,          -- T
  match_window_to   TIMESTAMP    NOT NULL,          -- T+1
  bucket_count      INT          NOT NULL,
  status            VARCHAR(24)  NOT NULL,
  revision          BIGINT       NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
  started_at TIMESTAMP NULL, finished_at TIMESTAMP NULL,
  CONSTRAINT uk_run UNIQUE (scenario_code, accounting_period, sequence_no)   -- 挡并发重复 Run
);

-- 序号分配计数器(修补⑦: 串行化 seq 分配, scheduler 与 REST 同一路径)
CREATE TABLE recon_run_seq (
  scenario_code VARCHAR(64) NOT NULL,
  accounting_period VARCHAR(16) NOT NULL,
  next_seq INT NOT NULL,
  PRIMARY KEY (scenario_code, accounting_period)
);

-- staging: 标准化统一模型(百万~千万级), 排序介质 = 本表 idx_merge
CREATE TABLE recon_record (
  record_id           VARCHAR(64) PRIMARY KEY,
  run_id              VARCHAR(64)  NOT NULL,
  segment_id          VARCHAR(32)  NOT NULL,        -- SEG1_MKT_ACCT | SEG2_ACCT_CHANNEL
  side                VARCHAR(8)   NOT NULL,        -- LEFT | RIGHT
  source_role         VARCHAR(16)  NOT NULL,        -- MARKETING | ACCOUNTING | CHANNEL
  match_key           VARCHAR(128) NULL,            -- SEG1=营销发放ID SEG2=渠道流水号
  group_key           VARCHAR(128) NOT NULL,        -- 发放单; 修补①: bucket 由它算
  bucket              INT          NOT NULL,        -- floorMod(hash(group_key), bucket_count)
  currency            CHAR(3)      NOT NULL,
  signed_amount_minor BIGINT       NOT NULL,        -- 带符号(红蓝字), 禁 double
  base_amount_minor   BIGINT       NULL,            -- 【留位·只读·MVP 不参与比较】
  fx_rate             DECIMAL(20,10) NULL,          -- 【留位·只读】
  fx_rate_time        TIMESTAMP    NULL,            -- 【留位·只读】
  fx_rate_source      VARCHAR(32)  NULL,            -- 【留位·只读】
  entry_type          VARCHAR(16)  NOT NULL,        -- ISSUE | REFUND | REVERSAL
  biz_status          VARCHAR(32)  NULL,            -- STATUS_MISMATCH 用
  biz_time            TIMESTAMP    NOT NULL,        -- 营销应发时点(TIMING)
  posting_time        TIMESTAMP    NULL,            -- 账务记账时点(TIMING/汇率锚点)
  claimed_run_id      VARCHAR(64)  NULL,            -- 修补⑧: TIMING 跨 Run 认领标记
  raw_ref             VARCHAR(256) NOT NULL,        -- 血缘 file:line / table:pk
  created_at          TIMESTAMP    NOT NULL,
  CONSTRAINT ck_ccy CHECK (CHAR_LENGTH(currency)=3),
  KEY idx_merge (run_id, segment_id, side, bucket, match_key),  -- sort-merge 游标(DB 落盘排序)
  KEY idx_group (run_id, segment_id, group_key)                 -- 1:N 聚合
);
CREATE TABLE recon_record_reject (
  id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64), segment_id VARCHAR(32),
  source_role VARCHAR(16), raw_ref VARCHAR(256), reason VARCHAR(128),
  raw_payload TEXT, created_at TIMESTAMP NOT NULL
);

-- 机器判差: fingerprint 幂等(修补②, 替换含空列的自然键)
CREATE TABLE discrepancy (
  discrepancy_id      VARCHAR(64) PRIMARY KEY,
  run_id              VARCHAR(64)  NOT NULL,
  segment_id          VARCHAR(32)  NOT NULL,
  type                VARCHAR(24)  NOT NULL,
  bridge_break_stage  VARCHAR(8)   NULL,            -- SEG1|SEG2 (BRIDGE_BROKEN 细分)
  fingerprint         CHAR(64)     NOT NULL,        -- SHA-256(canonical, null→'∅')
  group_key           VARCHAR(128) NULL,
  match_key           VARCHAR(128) NULL,
  currency            CHAR(3)      NULL,
  expected_amount_minor BIGINT NOT NULL DEFAULT 0,
  actual_amount_minor   BIGINT NOT NULL DEFAULT 0,
  delta_amount_minor    BIGINT NOT NULL DEFAULT 0,
  left_raw_ref        VARCHAR(256) NULL,
  right_raw_ref       VARCHAR(256) NULL,
  machine_result      TINYINT      NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_disc UNIQUE (run_id, fingerprint),  -- 空键类型也幂等(fingerprint 非空)
  KEY idx_disc (run_id, type)
);

-- 人工处置: 永不被重跑删除
CREATE TABLE discrepancy_disposition (
  id VARCHAR(64) PRIMARY KEY,
  fingerprint CHAR(64) NOT NULL,
  scenario_code VARCHAR(64) NOT NULL, accounting_period VARCHAR(16) NOT NULL, segment_id VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,                       -- RESOLVED|CLOSED|SUPPRESSED|REOPENED
  operator VARCHAR(64) NOT NULL, note VARCHAR(512) NULL,
  last_seen_run_id VARCHAR(64) NULL,
  version INT NOT NULL DEFAULT 0,                    -- 乐观锁
  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_disp UNIQUE (fingerprint)            -- 一差一处置
);

-- 冲正建议: 幂等键唯一, 永不被重跑删除, 无资金动作
CREATE TABLE reversal_suggestion (
  id VARCHAR(64) PRIMARY KEY,
  fingerprint CHAR(64) NOT NULL,
  run_id VARCHAR(64) NOT NULL, group_key VARCHAR(128) NULL,
  suggested_amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL,                       -- SUGGESTED|CONFIRMED|DISCARDED
  idempotency_key VARCHAR(128) NOT NULL,
  operator VARCHAR(64) NULL, created_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_rev UNIQUE (idempotency_key)
);

-- 处置/处理动作审计 + 外部幂等(嫁接·最简可用)
CREATE TABLE discrepancy_action (
  id VARCHAR(64) PRIMARY KEY,
  fingerprint CHAR(64) NOT NULL,
  action_type VARCHAR(24) NOT NULL,                 -- LEDGER|REVERSAL_SUGGESTION|MANUAL_RESOLVE|MANUAL_CLOSE
  idempotency_key VARCHAR(128) NOT NULL,
  payload TEXT NULL, operator VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_action UNIQUE (idempotency_key)
);

-- 告警发件箱(修补⑤: 外部副作用出 chunk 事务)
CREATE TABLE alert_outbox (
  id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL, fingerprint CHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',    -- PENDING|SENT|FAILED
  attempt INT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(128) NOT NULL,
  created_at TIMESTAMP NOT NULL, sent_at TIMESTAMP NULL,
  CONSTRAINT uk_outbox UNIQUE (idempotency_key)
);

-- 勾稽报表: 按 (segment, currency) 双向守恒
CREATE TABLE recon_report (
  report_id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL, segment_id VARCHAR(32) NOT NULL,  -- '__RUN__' 汇总行
  currency CHAR(3) NOT NULL,
  expected_total_minor BIGINT NOT NULL,             -- 左口径应对总额(signed net)
  matched_amount_minor BIGINT NOT NULL,
  amount_mismatch_minor BIGINT NOT NULL DEFAULT 0, missing_minor BIGINT NOT NULL DEFAULT 0,
  duplicate_minor BIGINT NOT NULL DEFAULT 0, extra_minor BIGINT NOT NULL DEFAULT 0,
  timing_minor BIGINT NOT NULL DEFAULT 0, status_mismatch_minor BIGINT NOT NULL DEFAULT 0,
  currency_mismatch_minor BIGINT NOT NULL DEFAULT 0, group_sum_mismatch_minor BIGINT NOT NULL DEFAULT 0,
  bridge_broken_minor BIGINT NOT NULL DEFAULT 0,
  right_side_total_minor BIGINT NOT NULL DEFAULT 0,  -- 右口径校验
  left_residual_minor BIGINT NOT NULL DEFAULT 0,     -- 应=0
  right_residual_minor BIGINT NOT NULL DEFAULT 0,    -- 应=0
  balanced TINYINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_report UNIQUE (run_id, segment_id, currency)
);
-- Spring Batch 元数据表 BATCH_* 用官方 schema-mysql.sql / schema-postgresql.sql 单独 Flyway 迁移
```

---

## 6. 桥接两段 + 1:N 聚合处理时序(Spring Batch 落法)

**Job `reconciliationJob`,参数 {runId, scenarioCode, period, seq}。两段责任链顺序执行(SEG1 → SEG2),各段跑 Step1~3,各自独立守恒。**

**Step0 `prepareRunStep`(tasklet)**
- `ReconRunRepository.claim(run)`:INSERT 命中 uk_run,重复即抛 Conflict(挡并发)。置 status=LOADING(revision+1)。
- **重跑清理(修补③:分批,非单大事务)**:调 `ReconRerunService.cleanBounded(runId)`——循环 `DELETE FROM recon_record WHERE run_id=? LIMIT 10000`、`DELETE FROM discrepancy WHERE run_id=? AND machine_result=1 LIMIT 10000`,每批独立提交直到 0 行;同样分批清 recon_record_reject / recon_report / alert_outbox(PENDING)。**绝不触碰** discrepancy_disposition / reversal_suggestion / discrepancy_action。

**Step1 `loadStep`(每 segment×side 一次,chunk)**
- reader=`SourceAdapterItemReader`(包 RecordCursor);processor=标准化 + `KeyExtractor.extract`(算 match_key)+ `KeyExtractor.groupKey` + `bucket = floorMod(hash(group_key), bucketCount)`(**修补①:桶键=group_key**);writer=`StagingRecordWriter`(JdbcTemplate `batchUpdate` 批插)。
- 畸形行 skip 到 recon_record_reject(带 raw_ref),不中断整批。可重启(chunk checkpoint)。

**修补①·桶/组对齐不变式(必须写进校验与文档)**
> `bucket = floorMod(hash(group_key), N)`,且 **match_key 必须是 group_key 的细分**(每个 match_key 属唯一 group_key,`group_key = f(match_key)` 良定义)。MVP 两段均满足 **match_key == group_key**(SEG1 都为 marketingIssueId,SEG2 都为 channelSerialNo)→ 同发放单/同流水号必落同桶,GROUP_SUM 聚合与 sort-merge join 都在单桶内完成,杜绝跨桶分裂假阳性。SEG2 的"发放单跨多条渠道流水号"roll-up 因渠道侧无 issueId,归 Non-goal(阶段二)。启动期校验:若某场景 group_key 粗于 match_key 且不满足 refine 关系,fail-fast 拒绝装配。

**Step2 `matchEvaluateStep`(partitioned)**
- `BucketPartitioner` 造 0..N-1 个 partition。
- 每 partition 一 chunk step:reader=**GroupReader**(修补·嫁接最简可用)——两条 DB 游标 `SELECT ... WHERE run_id=? AND segment_id=? AND side=? AND bucket=? ORDER BY match_key`(DB 落盘排序),`SortMergeJoiner` 归并,**以"整个 match_key/group 组 = 一个 item"发射**,避免同组跨 chunk 提交被切断。
- processor:`GroupSumMatchStrategy` 聚合(组内 signed 求和,流式,MatchGroup 只留聚合量)→ `DiscrepancyClassifier` + `DiscrepancyEvaluator` 判差(§9 优先级,一组只发一条主类型)。
- writer:`DiscrepancyUpsertWriter`——按 `uk_disc(run_id, fingerprint)` upsert(DuplicateKey 吞,幂等);触发 **TRANSACTIONAL handler**(LedgerHandler / ReversalSuggestionHandler)在同事务内;**AlertHandler 只写 alert_outbox**(不发)。
- JobRepository 存 partition checkpoint,重启只续跑未完成 partition。
- **数据倾斜**:排序由 DB 完成、归并流式,内存只驻当前组的聚合量;极端热点单组 → 二级 sub-bucket by record_id 或落盘分批(记为已知边界)。

**Step3 `reportStep`(tasklet)**:按 (segment, currency) 汇总,`ConservationChecker` 双向勾稽(§8),写 recon_report;守恒通过置 COMPLETED,否则 REPORT_IMBALANCE。

**Step4 `alertRelayStep`(tasklet,修补⑤)**:Job 提交后读 alert_outbox(status=PENDING),`AlertDispatcher` 至少一次投递(带 idempotency_key),成功置 SENT、失败置 FAILED+attempt(可由 `@Scheduled` 中继补投)。**外部告警彻底脱离可重试的 chunk 事务**。

---

## 7. 事务边界

| 阶段 | 事务边界 | 关键点 |
|---|---|---|
| prepareRunStep | claim 单短事务;清理**分批**多事务(每批 ≤1万行) | 修补③:禁整表大事务;不碰 disposition/reversal |
| loadStep | Spring Batch chunk 事务(每 chunk 提交一次 batchUpdate) | 断点续跑;skip 到 reject |
| matchEvaluateStep | 每 partition 独立、按 chunk 提交;discrepancy `uk_disc` upsert;TRANSACTIONAL handler 同事务;reversal_suggestion insertIfAbsent | **AlertHandler 不在此事务发送**,只 insert alert_outbox |
| reportStep | 单事务读汇总 + 写 recon_report + `save(run, revision)` 乐观锁置终态 | 守恒精确到分 |
| alertRelayStep | 每条 outbox 一短事务标 SENT | at-least-once + 幂等键;回滚只丢投递不丢账 |
| 人工核销(REST) | 独立在线事务(**绝不在批内**):写 discrepancy_disposition(version 乐观锁)+ DiscrepancyStateMachine 流转 + discrepancy_action 审计 | 冲突返回 409;不删机器判定 |
| seq 分配 | `recon_run_seq` 原子 `UPDATE ... SET next_seq=next_seq+1` 或 INSERT..ON DUPLICATE(修补⑦) | scheduler 与 REST 同一 `ReconLaunchService` 路径,无 MAX+1 竞态 |

---

## 8. 守恒自证(构造性 + 双向 + 多币种口径闭合)

> **本节 2026-08-17 按 M0 实现(`ConservationChecker`)回填**:原稿把左右口径共用一个对称 `matched` 导致右口径无法闭合;实现改为 **`matchedLeft` / `matchedRight` 双 matched 口径**(下述),left/right residual 均 by-construction ≡ 0。用户已确认此口径。

**核心:构造性双向守恒。** 每个组的两侧带符号额被**恰好一次**地路由到 (segment, currency) 桶内的左口径或右口径某一子项,故 residual 由构造恒为 0。**按 currency 分桶,跨币不相加;全程 signed 整数分,`MoneyMath.addExact` 溢出 fail-fast。**

**双 matched 口径(关键):**
- `matchedLeft`:仅**干净匹配组**(type==null、两侧同键同额同状态同时点)的左额。→ 即报表列 `matched_amount_minor` 的展示值。
- `matchedRight`:干净匹配组的右额 **+ 所有"两侧按键配上但有差"组的右额**(AMOUNT_MISMATCH/STATUS_MISMATCH/TIMING/GROUP_SUM_MISMATCH/DUPLICATE)。语义:从右侧(实发/实扣)完整性看,这些右额**确实配上了、只是有差**,故计入右口径 matched,而**不**污染左口径。

**左口径(应发侧完整性)**——差异组左额全额进对应差异列:
```
expected_total = matchedLeft + missing + amount_mismatch_left + group_sum_left
               + timing_left + status_left + duplicate_left
               + bridge_broken_left + currency_mismatch_left
left_residual  = expected_total − 上式右侧 ≡ 0
```

**右口径(实发/实扣侧完整性)**——EXTRA/右侧 bridge/右侧 currency 单列右式:
```
right_side_total = matchedRight + extra + bridge_broken_right + currency_mismatch_right
right_residual   = right_side_total − 上式右侧 ≡ 0
```

- 任一 residual ≠ 0 → `balanced=0` → Run=`REPORT_IMBALANCE`。**⚠️ 口径澄清(实现 javadoc 明确)**:residual≡0 是**构造性恒等**(左右均由同一批 per-group signed 额累加),故它**只抓"桶路由被改坏 / MoneyMath 溢出 / 未来会计代码回归",不证明 `DiscrepancyClassifier` 判定正确**。分类正确性由各差异桶的**数值断言 + 分类器自身单测**保证(不要把守恒当分类正确性的证明)。

**按 presence 的路由(实现细节):**
- `BOTH` 且 type==null → matchedLeft+=left、matchedRight+=right;`BOTH` 且有差 → matchedRight+=right、左额→对应差异列。
- `LEFT_ONLY` → MISSING(左额→missing)或 BRIDGE_BROKEN(左额→bridge_broken_left)。
- `RIGHT_ONLY` → EXTRA(右额→extra)或 BRIDGE_BROKEN(右额→bridge_broken_right)。
- 非 CURRENCY_MISMATCH 的 BOTH 组必须同币,否则 fail-fast(防跨币入错桶)。

**修补④·签名算术钉死**
- GROUP_SUM_MISMATCH 左侧贡献 = **左组 signed 和**(可为 0 或负);上报 delta = |左组和 − 右组和|(供运营,存 `Discrepancy.delta`),守恒等式只用左组和。0 和组(ISSUE+REFUND 抵消)贡献 0 不假阳性;负和组贡献负等式仍闭合。**已单测覆盖:组内混 ISSUE/REFUND/REVERSAL 使和为 0 与为负两场景。**

**修补·多币种守恒闭合**
- CURRENCY_MISMATCH 横跨两币种:**左额计入 leftCcy 桶的左口径 currency_mismatch_left,右额计入 rightCcy 桶的右口径 currency_mismatch_right,均不计入 matched**。每个币种桶左/右式各自闭合,无"一笔跨两桶破恒等"。报表 `currency_mismatch_minor` = 左右额之和(展示),另可附 (leftCcy,rightCcy) 明细行供运营。

---

## 9. 差异类型优先级 / 互斥(一组只发一条主类型)

判定顺序(EvaluationContext 携带 leftRole/rightRole/spineRole):
```
BRIDGE_BROKEN  >  CURRENCY_MISMATCH  >  DUPLICATE/EXTRA  >  GROUP_SUM_MISMATCH
              >  AMOUNT_MISMATCH  >  STATUS_MISMATCH  >  TIMING  >  MISSING
```
- **MISSING vs BRIDGE_BROKEN**:缺失侧 role == spineRole(账务) → `BRIDGE_BROKEN` 且置 `bridge_break_stage`(SEG1 缺=段1断、SEG2 缺=段2断);否则 `MISSING`。BRIDGE_BROKEN 压制 MISSING,不叠加。
- **CURRENCY_MISMATCH 短路**:两侧 currency 不一致,不进任何数值比较。
- **TIMING**:T~T+1 窗口内 posting_time 跨日命中 → TIMING,不判 MISSING;窗口所有权见修补⑧。
- **[待澄清 A3]** 此优先级是否符合业务口径,请用户确认。

---

## 10. 关键 ADR

- **ADR-1 责任链两两拆分 vs N 方统一** → 两两。spine 只能两两桥接;各段独立守恒 + BRIDGE_BROKEN 精确定位断段;避免 N 路 join 组合爆炸。代价:无单一三方视图(阶段二 roll-up)。
- **ADR-2 MVP 批处理不上流式**。次日/小时级 SLA + 账务守恒在有界批里更易保证;工作区无流式基建。Flink/Kafka 阶段三。**Spring Batch 是全库首次引入,须先 spike(见 §11 走骨架)**。
- **ADR-3 字段一步到位、算法分阶段**。DDL 留 fx_rate/base_amount_minor/1:N 位但注 `【留位·只读·不参与比较】`,币种不一致走 CURRENCY_MISMATCH,1:N 只到 GROUP_SUM_MISMATCH;阶段二加换算/下钻不改表。ArchUnit + 单测断言 MVP 判差不读留位列兜底。
- **ADR-4 桥接 spine 设计**。账务持双键;SEG1 join marketingIssueId、SEG2 join channelSerialNo;spine 缺→BRIDGE_BROKEN 分 stage,优先级高于 MISSING。
- **ADR-5 金额最小货币单位 long(分)**。全链路 signed bigint;Money 封装,禁 double、跨币种 compare 抛异常;ArchUnit 静态禁 double 进金额路径;`MoneyMath.addExact` 溢出 fail-fast(bigint≈92万亿元封顶,千万级安全)。
- **ADR-6 持久化 JdbcTemplate + Flyway expand-contract**,非 MyBatis-Plus。贴合全工作区约定;MP 单行 ORM 不适合千万级批插。**[待澄清 A6]** MP 是否硬约束——若强制,仅低频配置表用,staging/判差仍裸 JDBC。
- **ADR-7 重跑保护(最大产品风险)**。机器判差 / 人工处置 / 冲正建议**三表分离**;重跑只分批清 staging + OPEN 机器差异;人工痕迹永不被删。
- **ADR-8 recon-core 纯度**。四接口 + fact 三元模型(MatchGroup/DiscrepancyRule/EvaluationContext)一步到位;ArchUnit 门禁;DroolsEvaluator 误配 fail-fast。
- **ADR-9 差异身份用 fingerprint(修补②)**。`fingerprint = SHA-256(canonical: scenario|period|segment|type|coalesce(group_key,'∅')|coalesce(match_key,'∅')|coalesce(bridge_stage,'∅'))`;`uk_disc(run_id, fingerprint)` 对 BRIDGE_BROKEN/CURRENCY_MISMATCH 等空键类型仍幂等,并作为人工处置跨重跑 re-link 的稳定锚。
- **ADR-10 告警走事务性发件箱(修补⑤)**。DiscrepancyHandler 分 TRANSACTIONAL / EXTERNAL_SIDE_EFFECT;外部告警只在批内写 alert_outbox,批后中继投递,杜绝 chunk 回滚/重试重复触发外部副作用。
- **ADR-11 分桶键=group_key + match_key refine 不变式(修补①)**;**ADR-12 seq 分配串行化 + 重跑=同 run_id 重算、新账期尝试=新 seq(修补⑦)**。

---

## 11. 方法级改动清单(给 java-build-review 的实现输入)

> 停在定稿;本清单不写实现,是落地 backlog。按 **walking-skeleton 递进顺序**(修补⑥,给全新的 Spring Batch 降风险):

**里程碑 M0 垂直切片(不上 Spring Batch)**
- recon-core:`ReconRecord`/`Money`/`MatchKey`/`GroupKey`/`MatchGroup`/`Discrepancy`/`ReconReport` + 枚举;`SortMergeJoiner.join(Iterator,Iterator,MatchSink)`;`GroupSumMatchStrategy`;`ExactEvaluator`;`DiscrepancyClassifier.classify(MatchGroup, EvaluationContext)`;`ConservationChecker.check(...)`(双向);`MoneyMath.sumSignedMinor(Iterable)`(addExact 溢出 fail-fast);`Fingerprint.of(...)`(null-safe canonical + SHA-256)。
- 单测:单段单桶内存 join → AMOUNT_MISMATCH + 守恒闭合。**先证领域正确性。**
- `ArchitectureTest`(recon-core):门禁 + 禁 double。

**M1 持久化 + DB sort-merge(单桶)**
- 端口:`ReconRunRepository{claim; find; save(run, expectedRevision) throws Conflict}`、`ReconRecordRepository{batchInsert; cursor(runId,segmentId,side,bucket) sorted; deleteByRunBounded(limit)}`、`DiscrepancyRepository{upsertByFingerprint; deleteOpenMachineByRunBounded(limit); listByRun}`、`DiscrepancyDispositionRepository`、`ReversalSuggestionRepository{insertIfAbsent(idempotencyKey)}`、`ReconReportRepository`、`AlertOutboxRepository`。
- recon-source-db:`DbSourceAdapter`(keyset 游标)。recon-batch:`Jdbc*Store`(照搬工作区 revision 条件更新 + DuplicateKeyException→Conflict 范式)。Flyway V1__recon_schema.sql + V2__batch_metadata.sql。

**M2 Spring Batch(单 matchEvaluate step,无 partition)**
- recon-batch:`ReconBatchApplication`;`BatchConfig` Job=Step0 prepareRunStep → Step1 loadStep → Step2 matchEvaluateStep(单线程)→ Step3 reportStep;`SourceAdapterItemReader`/`StagingRecordWriter`/`GroupReader`(整组=一 item)/`EvaluateProcessor`/`DiscrepancyUpsertWriter`;`ReportTasklet`。JobRepository 断点。**先跑通 spike。**

**M3 分桶并行**
- `BucketPartitioner`(0..N-1)+ `TaskExecutorPartitionHandler`(有界池);matchEvaluateStep 改 partitioned;`ReconRerunService.cleanBounded`(分批删除);skip 到 reject 表。倾斜二级 sub-bucket(边界)。

**M4 责任链两段 + 桥接**
- recon-scenario:`SpineBridgeKeyExtractor`、`MarketingThreeWayScenario`(双 segment);SEG1/SEG2 各跑 Step1~3;`ExactEvaluator`/`ToleranceEvaluator` 产 BRIDGE_BROKEN(分 stage)/MISSING/DUPLICATE/EXTRA/GROUP_SUM/STATUS/TIMING;`EvaluatorFactory`(DROOLS fail-fast);`DroolsEvaluator` 占位。

**M5 处理链 + 人工核销 + outbox**
- recon-handler:`LedgerHandler`(TRANSACTIONAL)、`ReversalSuggestionHandler`(TRANSACTIONAL,insertIfAbsent)、`AlertHandler`(EXTERNAL,写 outbox)、`ManualClearingService`(disposition version 乐观锁 + StateMachine + discrepancy_action)、`FlowableTicketHandler` 占位;recon-batch:`Step4 alertRelayStep` + `AlertDispatcher`;`DiscrepancyController`(POST /recon/runs、POST /recon/runs/{id}/rerun、POST /recon/discrepancies/{id}/resolve|close、GET /recon/runs/{id}/report);`ReconLaunchService`(seq 经 recon_run_seq 原子分配)、`ReconScheduler`(@Scheduled)。

**M6 加固**
- recon-source-csv:`CsvSourceAdapter`(BOM/编码/行号,reject);H2 集测:重跑幂等 + **重跑不覆盖人工核销** + 守恒闭合 + 八类差异可复现;各外圈模块各一份 ArchitectureTest。

---

## 12. 验收标准

1. 给定测试集能发现并正确分类 AMOUNT_MISMATCH / MISSING / DUPLICATE / EXTRA / BRIDGE_BROKEN(段1/段2 分别)/ GROUP_SUM_MISMATCH / CURRENCY_MISMATCH / STATUS_MISMATCH / TIMING。
2. 两段各自独立产差;账务 spine 缺记录时 bridge_break_stage 可区分断哪段。
3. 金额全为 long(分)/BigDecimal,链路无 double(ArchUnit 佐证);跨币种不进直接数值比较;fx_rate/base_amount_minor 存在但 MVP 只读不参与。
4. signed_amount_minor + entry_type 支持红蓝字;组内签名求和触发 GROUP_SUM_MISMATCH;和为 0/负场景守恒不假阳性(专项单测)。
5. Run 唯一约束挡并发;重跑分批清机器结果再重算,结果一致;**重跑绝不覆盖 RESOLVED/CLOSED 处置与已生成冲正建议**。
6. Discrepancy 可人工核销 OPEN→RESOLVED/CLOSED(记操作人/时间/备注,version 乐观锁,409 冲突);冲正仅 SUGGESTED、幂等、重复触发不重复生成。
7. **ReconReport 双向守恒精确到分闭合(left_residual=right_residual=0),不满足置 REPORT_IMBALANCE**;多币种分桶不跨币相加;CURRENCY_MISMATCH 左右额分别落各自币种桶不破恒等。
8. 每条 record/Discrepancy 带 raw_ref 血缘;TIMING 窗口内匹配上的不判 MISSING。
9. 告警经 alert_outbox 投递,批 chunk 重试不重复发外部告警。
10. recon-core ArchUnit 门禁通过;fingerprint 对空键类型幂等(BRIDGE_BROKEN/CURRENCY_MISMATCH 重跑不产生重复行)。
11. 本阶段交付物 = 可评审设计定稿(本文档),停在定稿。

---

## 13. 取舍(综合后的净取舍)

1. **责任链两两拆分**:换插件化 + 各段独立守恒 + BRIDGE_BROKEN 精确定位;代价 = 无单一三方合并视图(阶段二 roll-up)。
2. **fingerprint 替代自然键**:换空键类型幂等 + 跨重跑稳定身份(人工处置 re-link);代价 = 多一次 hash 计算 + type 变更(MISSING→AMOUNT_MISMATCH)会使 fingerprint 变、旧处置悬空(见待澄清 A1)。
3. **桶键=group_key + match_key==group_key(MVP)**:换 GROUP_SUM 与 join 同桶正确;代价 = SEG2 发放单跨渠道流水号 roll-up 归 Non-goal。
4. **告警走 outbox**:换外部副作用与 chunk 事务解耦、可靠不重发;代价 = 多一张表 + 一个中继 step,告警从"实时"变"批后至少一次"。
5. **分批删除**:换避免千万级大事务长锁;代价 = 清理耗时略增、需循环控制。
6. **构造性双向守恒**:换"报表不自洽=bug"可执行门禁;代价 = 分类必须严格 partition,评估器实现负担上移到 Classifier。
7. **DB 落盘 sort-merge**:满足不全量 load 内存;代价 = 每桶两次索引扫 + 排序 IO,热点大组需二级 sub-bucket。
8. **JdbcTemplate + Flyway**:贴合工作区、适配千万级批插;代价 = 手写 SQL/RowMapper,放弃 ORM 便利。
9. **三表分离 + fingerprint re-link**:换重跑绝不覆盖人工工作;代价 = 重算后失踪差异的收敛规则(标 STALE/自动关闭)复杂度上移(待澄清 A1)。

---

## 14. 口径决议(用户 2026-08-17 拍板,均采纳默认)

> 原待澄清 A0–A8 已按推荐默认定稿,实现阶段以此为准;后续如与真实业务冲突再改。

- **A0**〔已定〕幂等范式只依赖"唯一键 + DuplicateKeyException 吞 + `save(entity, expectedRevision)` 条件更新失败抛 Conflict",不依赖具名类;M1 落地前 `ls` risk-platform 确认具名参考类,范式不受影响。
- **A1**〔已定·最大产品风险〕① 人工已 RESOLVED 的差异重算后又出现 → **re-link 保持 RESOLVED**(不重开)。② 人工处置过但重算后消失的差异 → **标 STALE 自动关闭 + 留审计**。③ 差异 type 变更(MISSING→AMOUNT_MISMATCH)致 fingerprint 变 → **旧处置标 STALE、新差异 OPEN**。三条写进 `ManualClearingService` / 重跑收敛逻辑并由 M6 集测覆盖。
- **A2**〔已定〕MVP 只出两段独立报表;三方合并 roll-up 视图归**阶段二**。
- **A3**〔已定〕差异类型优先级按 §9 定稿。
- **A4**〔已定〕TIMING 窗口:记录归属由 posting_time 落入唯一账期确定,look-ahead 窗口仅用于匹配不认领对手,匹配后置 `claimed_run_id` 防相邻 Run 重复匹配。
- **A5**〔已定〕**日账期 `YYYY-MM-DD` + cutoff**;cutoff 边界按"posting_time ≤ cutoff 归本期"(闭区间)。
- **A6**〔已定〕持久化用 **JdbcTemplate + Flyway**(非 MyBatis-Plus),贴合工作区约定并适配千万级批插。
- **A7**〔已定〕expected_total 权威侧:**SEG1=营销应发、SEG2=账务实发**。
- **A8**〔已定〕默认分桶数 **N=64**(可配),桶行数超均值 5× 记为倾斜触发二级 sub-bucket;退款晚到跨账期先按**本期 TIMING**、超 T+1 窗口才判下期 MISSING。
