# recon-platform · 通用自动对账系统

一个**可扩展、可插拔**的自动对账（Reconciliation）平台。首要落地场景是**营销发钱三方对账**——校验「营销引擎判定应发」↔「账务系统实际发放」↔「第三方渠道实际扣款/到账」是否对得上，并把差异定位、分类、闭环处理；同一套引擎可低成本接入支付渠道对账、订单-支付对账等其它场景。

技术基线：**Java 21 / Spring Boot 3.3.5 / Maven 多模块（`com.lrj.recon:recon-platform`）**。构建用仓库自带的 `./mvnw`（wrapper），勿用系统 mvn。

> 完整架构决策与字段级设计见 **[`docs/design/RECON_MVP_DESIGN.md`](docs/design/RECON_MVP_DESIGN.md)**（judge-panel 综合定稿，含领域模型、DDL、四接口签名、桥接两段匹配时序、ADR、口径决议 A0–A8）。

---

## 架构一句话

对账被抽象成**一条稳定流水线 + 四个可插拔点**——通用性来自"分离不变量与变化点"：

```
拉取 → 标准化 → 勾兑匹配 → 差异判定 → 差异分类 → 差异处理 → 报表/复核闭环
 ①        ②(标准化)   ②(MatchKey)   ③(判差)                  ④
```

| 可插拔点 | 接口（recon-core `spi`） | 变化的东西 |
|---|---|---|
| ① 数据源 | `SourceAdapter` | DB / CSV文件 / API / MQ |
| ② 勾兑 | `KeyExtractor` + `MatchStrategy` | 单键 / 组合键 / 桥接 / 1:N 聚合 |
| ③ 判差 | `DiscrepancyEvaluator` | 精确 / 容差 / （阶段二 Drools） |
| ④ 处理 | `DiscrepancyHandler` | 告警 / 差异台账 / 冲正建议 / 人工核销 |

**首要场景（营销三方对账）**拆成责任链式两两对账：`SEG1 营销↔账务`（join 营销发放ID）、`SEG2 账务↔渠道`（join 渠道流水号），账务侧作为 **spine（桥梁）**同时持有两键；账务缺记录 → `BRIDGE_BROKEN` 精确定位断哪段。

## 模块与依赖方向（依赖箭头一律指向 `recon-core`）

```
recon-core             纯 Java零框架：领域模型 + SPI + 10 个持久化端口 + 领域服务
   ↑       ↑       ↑       ↑
source-db source-csv scenario handler   DB/CSV 数据源 / 场景桥接 / 处理链
    \          \       |       /
              recon-batch        Spring Boot 组合根：源路由 + Batch + JDBC + REST + 调度 + outbox
```

`recon-core` 由 **ArchUnit 门禁**强制零框架：禁依赖 Spring / Spring Batch / Drools / JDBC / CSV / Flowable / JPA，且**金额路径禁 `double`**。接新数据源或规则只写外圈实现，不改内核。

## 快速开始

```bash
# 本机(macOS)需先指向 JDK 21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./mvnw -q test                       # 全量编译 + 跑测试(集成测试用 H2 内存库,免 Docker)
./mvnw -q -pl recon-core test        # 只跑领域内核测试
./mvnw -q -pl recon-source-csv -am test # CSV 适配器 + 架构门禁
./mvnw -pl recon-core test -Dtest=ConservationCheckerTest          # 单个测试类
./mvnw -pl recon-core test -Dtest=DiscrepancyClassifierTest#missing # 单个方法
./mvnw -q clean package              # 全量构建打包
```

- 集成测试默认 **H2 内存库**（MySQL 兼容模式）+ Flyway 迁移，无需外部依赖即可跑绿。
- 生产 DDL 保持 **MySQL 8 / PostgreSQL / H2 通用**；Spring Batch 元数据按方言拆在 `recon-batch/src/main/resources/db/batch/{h2,mysql,postgresql}`。

## M5 REST 与调度

默认场景为 `MARKETING_3WAY`，发起与重跑都使用 `recon.launch.default-job` 的确定性映射；REST 不能把未知场景或任意 Job 拼在一起运行。

```bash
# 发起；bucketCount 可省略，合法范围 1..4096
curl -X POST http://localhost:8080/recon/runs \
  -H 'Content-Type: application/json' \
  -d '{"scenarioCode":"MARKETING_3WAY","accountingPeriod":"2026-08-18","bucketCount":64}'

# 同一 runId 重跑（保留人工处置、冲正建议和审计）
curl -X POST http://localhost:8080/recon/runs/MARKETING_3WAY:2026-08-18:1/rerun

# 人工核销；operator 最长 64、note 最长 512，expectedVersion 用于乐观锁
curl -X POST http://localhost:8080/recon/discrepancies/{discrepancyId}/resolve \
  -H 'Content-Type: application/json' \
  -d '{"operator":"ops","note":"verified","expectedVersion":0}'

curl http://localhost:8080/recon/runs/MARKETING_3WAY:2026-08-18:1/report
```

调度默认关闭。生产可设置 `RECON_SCHEDULER_ENABLED=true`，再配置 `RECON_SCHEDULER_LAUNCH_CRON`；告警默认由日志 dispatcher 接收，接真实通道时提供一个 `@Primary AlertDispatcher`。完整默认值见 `recon-batch/src/main/resources/application.yml`。

## M6 CSV 数据源

三方 Job 默认仍读取 DB。切换为 CSV 时设置：

```bash
export RECON_M4_SOURCE_TYPE=csv-file
export RECON_M4_MARKETING_FILE=/data/recon/marketing.csv
export RECON_M4_ACCOUNTING_FILE=/data/recon/accounting.csv
export RECON_M4_CHANNEL_FILE=/data/recon/channel.csv
export RECON_M4_CSV_CHARSET=UTF-8
export RECON_M4_CSV_DELIMITER=,
```

CSV 必须有表头；字段约定如下：

| 文件 | 必填表头 |
|---|---|
| marketing | `id,order_no,issue_id,ccy,amount_minor,entry_type,biz_status,biz_time,posting_time` |
| accounting | `id,order_no,issue_id,channel_serial_no,ccy,amount_minor,entry_type,biz_status,biz_time,posting_time` |
| channel | `id,channel_serial_no,ccy,amount_minor,entry_type,biz_status,biz_time,posting_time` |

- `amount_minor` 是带符号 `long`，时间为 ISO-8601 instant（如 `2026-08-18T10:00:00Z`）；`biz_time` 必填，`posting_time` 可空。
- 支持 UTF-8/UTF-16/UTF-32 BOM、显式字符集、单字符分隔符、标准 CSV 引号和跨行字段。BOM 与显式字符集冲突时启动即失败。
- 业务字段畸形行写入 `recon_record_reject` 后继续；不可恢复的 CSV 语法或编码损坏会记录当前行 reject 并停止该文件，避免错位读取。
- `raw_ref` 为绝对文件路径加物理行号；跨行记录形如 `file.csv:2-3`。重跑会先分批清理旧 reject，避免重复累积。

## 关键设计不变量（红线）

- **金额**全链路最小货币单位 `long`（分，带符号红蓝字），禁 `double`；跨币种不可直接比，`MoneyMath.addExact` 溢出 fail-fast。
- **幂等**：每个 Run 有唯一键（scenario+账期+序号），重跑先分批清理机器结果再重算；差异身份用 `fingerprint`（SHA-256，null 键也幂等）。
- **保护人工核销（最大产品风险）**：机器判差 / 人工处置 / 冲正建议**三表分离**，`discrepancy_disposition` 表**没有删除方法**，重跑物理上碰不到人工痕迹。
- **守恒自证**：每次 Run 出勾稽报表，按币种分桶做**构造性双向守恒**（左/右口径 residual 均 ≡ 0），不闭合 → `REPORT_IMBALANCE`（视为对账逻辑本身有 bug）。
- **差异优先级**：一组只发一条主类型，`BRIDGE_BROKEN > CURRENCY_MISMATCH > DUPLICATE/EXTRA > GROUP_SUM_MISMATCH > AMOUNT_MISMATCH > STATUS_MISMATCH > TIMING > MISSING`。
- **血缘**：每条 record/差异带 `raw_ref`（文件:行号 / 表:主键）可追溯。

**差异类型**：`AMOUNT_MISMATCH`（金额不符）、`MISSING`（应发未发/漏记/漏扣）、`DUPLICATE`（重复）、`EXTRA`（多出）、`GROUP_SUM_MISMATCH`（发放单级总额不符）、`BRIDGE_BROKEN`（账务 spine 断链，分段1/段2）、`CURRENCY_MISMATCH`、`STATUS_MISMATCH`、`TIMING`（跨日错位）、`FX_RATE_DIFF`（留位，阶段二）。

## 分阶段路线图（walking-skeleton）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| **M0** | recon-core 纯领域内核（内存 join 证正确性） | ✅ |
| **M1** | 7 持久化端口 + DbSourceAdapter + Jdbc*Store + Flyway DDL | ✅ |
| **M2** | 引入 Spring Batch，单线程 Job 端到端走通（H2） | ✅ |
| **M3** | 分桶并行（BucketPartitioner + per-bucket 索引有序游标）、守恒单遍累计 | ✅ |
| **M4** | 两段桥接场景（`SpineBridgeKeyExtractor` + 营销三方场景装配） | ✅ |
| **M5** | 处理链 + 人工核销状态机 + 告警 outbox 中继 + REST | ✅ |
| **M6** | CSV 源适配器 + 加固 + 全链路集成测试 | ✅ |

> 阶段二（平台化）：ReconScenario 配置驱动、Drools 判差、对接 Flowable 差错工单。
> 阶段三（按需）：Flink 流式做近实时预警，批处理仍是权威定账。

## 不在 MVP 范围（Non-goals）

DSL 规则平台、Flink/Kafka 流式、跨币种汇率换算算法（`fx_*` 字段仅留位只读）、1:N 明细级下钻、自动冲正执行（仅生成建议待人工确认）。
