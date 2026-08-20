# 营销三方对账 · 接入手册(MARKETING_3WAY · DB 源)

> 把营销活动系统的「金额发放」接入通用对账平台,做 **营销 ↔ 账务 ↔ 渠道** 三方对账。
> 适用组合:**DB 表接入 + 完整三方**(平台已原生支持内置场景 `MARKETING_3WAY` + `marketingThreeWayJob`,本手册只讲接入,不改代码)。
> 权威口径:`docs/design/RECON_MVP_DESIGN.md`;架构说明见 `CLAUDE.md`。

## 1. 你在对账模型里的位置

营销对账是**三方两段桥接**,账务是枢纽(spine)同时持两个键:

```
        issue_id(发放ID)              channel_serial_no(渠道流水号)
   营销 ───────────────► 账务(spine) ───────────────► 渠道
  marketing              accounting                  channel
   SEG1 左                ▲ 被读两次                    SEG2 右
                          SEG1 投 issue_id + order_no
                          SEG2 投 channel_serial_no
```

- **SEG1 营销↔账务**:按 `issue_id`(营销发放ID)勾兑;`order_no`(发放单号)作 1:N 分组键。
- **SEG2 账务↔渠道**:按 `channel_serial_no`(渠道流水号)勾兑(match=group)。
- **账务表是 spine**:一张表必须**同时带 `issue_id`、`channel_serial_no`、`order_no` 三个键**;缺任一键 → 对应段桥断(`BRIDGE_BROKEN`)。

## 2. 源表建表 DDL

> 这三张表是**数据接入层**(上游/对账库),**不进 recon 的 Flyway**——源表由上游负责。

```sql
-- 营销发放明细(SEG1 左)
CREATE TABLE recon_src_marketing (
  id                VARCHAR(64)  PRIMARY KEY,   -- 唯一主键,keyset 游标按它分页(必须唯一稳定)
  order_no          VARCHAR(128) NOT NULL,      -- 发放单号 = group_key(1:N)
  issue_id          VARCHAR(128) NOT NULL,      -- 营销发放ID = SEG1 勾兑键
  ccy               CHAR(3)      NOT NULL,
  amount_minor      BIGINT       NOT NULL,      -- 带符号分:发放正/退款负,禁小数
  entry_type        VARCHAR(16)  NOT NULL,      -- ISSUE/REFUND/REVERSAL(仅血缘,不定符号)
  biz_status        VARCHAR(32),
  biz_time          TIMESTAMP    NOT NULL,
  posting_time      TIMESTAMP,
  KEY idx_mkt_issue (issue_id)                  -- 供排查/同步用(对账读全表不需要)
);

-- 账务明细(spine,SEG1 右 + SEG2 左)—— 必须同时带三个键
CREATE TABLE recon_src_accounting (
  id                VARCHAR(64)  PRIMARY KEY,
  order_no          VARCHAR(128) NOT NULL,      -- 发放单号(group_key)
  issue_id          VARCHAR(128) NOT NULL,      -- ← 对营销(SEG1)
  channel_serial_no VARCHAR(128) NOT NULL,      -- ← 对渠道(SEG2)
  ccy               CHAR(3)      NOT NULL,
  amount_minor      BIGINT       NOT NULL,
  entry_type        VARCHAR(16)  NOT NULL,
  biz_status        VARCHAR(32),
  biz_time          TIMESTAMP    NOT NULL,
  posting_time      TIMESTAMP
);

-- 渠道流水(SEG2 右)
CREATE TABLE recon_src_channel (
  id                VARCHAR(64)  PRIMARY KEY,
  channel_serial_no VARCHAR(128) NOT NULL,      -- 渠道流水号 = SEG2 勾兑键(match=group)
  ccy               CHAR(3)      NOT NULL,
  amount_minor      BIGINT       NOT NULL,
  entry_type        VARCHAR(16)  NOT NULL,
  biz_status        VARCHAR(32),
  biz_time          TIMESTAMP    NOT NULL,
  posting_time      TIMESTAMP
);
```

## 3. 字段映射(你的三个系统 → 三张表)

| 来源 | 你系统里的字段 | → 对账列 |
|---|---|---|
| 营销平台 | 发放流水主键 / 发放单号 / 发放ID / 金额(元→分) / 币种 / 发放或退款 / 发放时间 | id / order_no / issue_id / amount_minor / ccy / entry_type / biz_time |
| 账务系统 | 记账主键 / **同一发放单号** / **同一发放ID** / **渠道流水号** / 入账金额 / 币种 / 时间 | id / order_no / issue_id / **channel_serial_no** / amount_minor / ccy / biz_time |
| 渠道系统 | 渠道流水主键 / 渠道流水号 / 金额 / 币种 / 时间 | id / channel_serial_no / amount_minor / ccy / biz_time |

## 4. 接入约束(踩坑点,必须遵守)

1. **账务表带全三键**,且 `issue_id`/`channel_serial_no` 口径与营销/渠道**逐字一致**(勾兑靠字符串相等)。
2. **源表只放「当前账期待对账」数据**——对账**读全表**(`SELECT * … WHERE id>? ORDER BY id`,不按 biz_time 过滤,窗口只是 Run 元数据)。每次对账前用 ETL 把当账期三侧数据 load 进这三张表(对完归档/truncate),或用按账期分区表 + 视图指向当期。
3. **金额带符号、单位分**(发放正/退款负);`entry_type` 只做血缘展示,**不二次定符号**。
4. **`id` 唯一稳定**:keyset 游标按 id 分页推进,重复/漂移的 id 会漏读或错位。
5. **函数性 refine**:同一 `issue_id` 只能属唯一 `order_no`(账务侧尤其);违反会产**假差异**且守恒抓不到——见 KI-6,用 `GET /recon/runs/{id}/refine-violations` 定期扫。

## 5. 数据同步策略(三选一)

- **最简 · 每日 ETL load**:调度作业从三个系统查当账期数据 `INSERT … SELECT` 进三张表,对账跑完归档。
- **CDC 增量**:营销/账务/渠道库开 binlog → Canal/Debezium → 落这三张表,T+1 对账切一刀。
- **视图直连**:三侧数据本在同库时,建三个视图映射列名(零 ETL,注意对账读视图=读原表的负载)。

## 6. 配置 + 发起 + 验证

```bash
# 配置指向你的表(默认名即这三张,若同名可不配)
RECON_M4_SOURCE_TYPE=db
RECON_M4_MARKETING_TABLE=recon_src_marketing
RECON_M4_ACCOUNTING_TABLE=recon_src_accounting
RECON_M4_CHANNEL_TABLE=recon_src_channel

# 发起对账(账期 = 目标日)
curl -X POST http://<backend>/recon/runs -H 'Content-Type: application/json' \
  -d '{"scenarioCode":"MARKETING_3WAY","accountingPeriod":"2026-08-20","jobName":"marketingThreeWayJob","bucketCount":64}'

# 验证
GET /recon/runs/{runId}/report          # 每币种每段守恒(balanced)
GET /recon/discrepancies?runId={runId}  # 差异清单(金额不符/桥断/单边…)
# 差异详情里的 SUGGESTED 冲正建议 → 冲正审批页 通过/驳回(需 recon.workflow.flowable.enabled=true)
```

## 7. 生产化 checklist

- [ ] 账务表三键齐全且口径一致;`refine-violations` 扫描无违规。
- [ ] 金额均为带符号最小单位整数(分),无小数/浮点。
- [ ] 源表每账期只放当期数据(ETL load / 分区视图)。
- [ ] 幂等重跑:同账期重跑不覆盖人工核销(平台已保证);ETL load 幂等。
- [ ] 监控:接 A4 Prometheus(`recon_job_failures_total`、对账时长、自动对平率)。
- [ ] 告警:配 `RECON_ALERT_WEBHOOK_URL` 把差异告警发钉钉/飞书。
- [ ] 鉴权:生产走 `secure` profile(Casdoor),operator 从 JWT 取。

---

## 附:字段来源对照(接入时逐项确认)

| 对账列 | marketing | accounting | channel | 说明 |
|---|---|---|---|---|
| id | ✅ | ✅ | ✅ | 各自唯一主键 |
| order_no | ✅ | ✅ | — | 发放单号(1:N 分组) |
| issue_id | ✅ | ✅ | — | 营销发放ID(SEG1 键) |
| channel_serial_no | — | ✅ | ✅ | 渠道流水号(SEG2 键) |
| amount_minor | ✅ | ✅ | ✅ | 带符号分 |
| ccy | ✅ | ✅ | ✅ | 币种 |
| entry_type | ✅ | ✅ | ✅ | ISSUE/REFUND/REVERSAL |
| biz_time | ✅ | ✅ | ✅ | 业务时间 |
