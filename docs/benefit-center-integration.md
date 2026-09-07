# 权益中台对账与纠错接入

`benefit-center` 通过 `benefit.fulfillment-event.v1` 输出履约事实，营销通过
`marketing.award-expected.v1` 输出入队事务内的应发事实。对账平台先落独立 ODS，再按权益是否具有货币含义分流，避免为券、兑换码或实物伪造币种和 `0` 金额。

## 事实分流

| 权益 | 对账模型 | 当前落地 |
|---|---|---|
| CASH | `BENEFIT_CASH_3WAY`：营销应发 ↔ 内部账务 ↔ 渠道到账/扣款 | tenant/window 谓词已下推，内置场景可按租户发起 |
| COUPON/SERVICE_VOUCHER/REDEMPTION_CODE/PHYSICAL | `ENTITLEMENT_FULFILLMENT`：issueId、SKU、quantity、status、providerRef | 独立数量/状态批作业，差异投影为 `measureKind=QUANTITY` |

每个 topic 有独立 inbox consumer namespace，同一消费者内按 `eventId + payloadHash` 幂等：完全重放被忽略，相同 eventId 的不同 payload 被拒绝。仅接受兼容的 schema major。现金应发只来自 `marketing.award-expected.v1`；履约 `INTERNAL` 和 `PROVIDER` 分别落账务与渠道 ODS，禁止用一条履约事件冒充三方。

Run 必须携带 `tenantId`（权益场景拒绝 `legacy`/缺失值），`RunKey` 和 `SourceReadContext` 同时携带租户、账期窗口。DB reader 在每一页 keyset SQL 中下推 `tenant_id = ?` 和事件时间谓词，不会先全租户扫描后再内存过滤。

手工验证时使用通用发起接口，不能把权益场景套到默认 `marketingThreeWayJob`：

```bash
curl -X POST http://localhost:8088/recon/runs \
  -H 'Content-Type: application/json' \
  -d '{"scenarioCode":"BENEFIT_CASH_3WAY","accountingPeriod":"2026-09-05","tenantId":"dev-tenant"}'

curl -X POST http://localhost:8088/recon/runs \
  -H 'Content-Type: application/json' \
  -d '{"scenarioCode":"ENTITLEMENT_FULFILLMENT","accountingPeriod":"2026-09-05","tenantId":"dev-tenant"}'
```

`ReconLaunchService` 会把 `ENTITLEMENT_FULFILLMENT` 映射到独立数量/状态 Job；现金场景仍使用通用金额引擎，并强制场景定义的三个 DB source 同时具备 `tenantColumn=tenant_id` 与账期窗口列。管理台改坏这两个谓词时，发起会直接失败而不是退化为全租户扫描。

## 受控 remediation

1. `POST /recon/benefit-remediations` 只生成建议，按 `(tenant, discrepancy, action)` 幂等。
2. `approve`/`reject` 要求审批引用；secure profile 下写接口需要 `recon.dispose`。
3. 批准后同事务写 command outbox；relay 默认关闭，开启后发布 `benefit.remediation.command.v1`。
4. outbox 使用 lease + CAS 支持多实例和崩溃恢复；中台仍会再次验证原 operation：`UNKNOWN` 禁止补发，明确成功才可冲正。
5. `benefit.remediation.result.v1` 结果按 command 幂等消费并单调收敛建议状态。

配置开关均默认关闭：

```text
RECON_BENEFIT_ODS_KAFKA_ENABLED=false
RECON_REMEDIATION_RELAY_ENABLED=false
RECON_REMEDIATION_RESULT_CONSUMER_ENABLED=false
```

## 启用顺序与回滚

1. 先部署营销与权益契约字段：营销应发包含 `benefitType/currency`，履约包含 `clientItemId/sourceRequestId`。
2. 部署 recon 迁移和应用，保持 `RECON_BENEFIT_ODS_KAFKA_ENABLED=false`，先验证新表/索引和运行权限。
3. 开启 ODS consumer，观察两个 consumer group 的 lag、inbox payload conflict 和拒绝消息。
4. 按单租户手工发起 `BENEFIT_CASH_3WAY` / `ENTITLEMENT_FULFILLMENT`，验证样本守恒和迟到数据重跑，再考虑配置定时任务。

若启用定时发起，必须同时显式设置 `RECON_SCHEDULER_SCENARIO` 与 `RECON_SCHEDULER_TENANT_ID`；权益场景不得沿用默认 tenant `legacy`。

回滚时先停权益场景定时发起，再设 `RECON_BENEFIT_ODS_KAFKA_ENABLED=false` 停两个 consumer。已落 ODS、inbox 和差异保留审计，不删表、不回退偏移；恢复时使用原 consumer group 继续消费。Remediation relay/result consumer 是独立开关，不随 ODS consumer 自动打开。

## 交叉系统键

- `tenantId`：业务租户，权益 Run 的必填隔离键。
- `campaignId + definitionVersion`：营销定义版本。
- `benefitSkuId + skuVersion`：权益品版本。
- `sourceRequestId`：跨系统幂等键，在差异读模型中投影为 `marketingSourceRequestId`。
- `clientItemId`：营销应发与权益履约的稳定项级 join key。
- `awardOrderNo`：在差异读模型中投影为 `benefitOrderNo`。

## 运行边界

消费开关默认仍关，生产启用前需在目标 MySQL/PostgreSQL 上验证迁移、索引选择性、迟到数据重跑和峰值账期容量；本地 H2 结果不代表生产 SLO。非金额差异已进入统一差异/人工处置读模型，但金额守恒报表不适用于数量模型。

当前 `BenefitOdsConsumer` 只接受 `FULFILLMENT_EXPECTED`、`FULFILLMENT_INTERNAL`、`FULFILLMENT_PROVIDER` 三类发奖履约事实，并依赖 payload 中的 `factType`、`benefitType` 等字段。权益中心钱包命令发布的 `FULFILLMENT_WALLET` 使用独立的 `WalletFulfillmentEvent` 结构，不能直接进入这个 consumer；上线钱包事件前必须为其使用隔离 topic/consumer，或先完成兼容改造和契约测试。若把两类事件混投到当前履约 topic，消息会进入 retry/DLT，而不会成为有效对账事实。
