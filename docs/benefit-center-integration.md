# 权益中台对账与纠错接入

`benefit-center` 通过 `benefit.fulfillment-event.v1` 输出履约事实。对账平台先落独立 ODS，再按权益是否具有货币含义分流，避免为券、兑换码或实物伪造币种和 `0` 金额。

## 事实分流

| 权益 | 对账模型 | 当前落地 |
|---|---|---|
| CASH | `BENEFIT_CASH_3WAY`：中台应发 ↔ 内部账务 ↔ 渠道到账/扣款 | 场景定义、ODS 与种子已提供；默认 disabled |
| COUPON/SERVICE_VOUCHER/REDEMPTION_CODE/PHYSICAL | `ENTITLEMENT_FULFILLMENT`：issueId、SKU、quantity、status、providerRef | 独立模型与分类器已提供；完整批作业后续启用 |

同一个外部事件按 `eventId + payloadHash` 进入 inbox：完全重放被忽略，相同 eventId 的不同 payload 被拒绝。仅接受兼容的 schema major；现金内部应发、账务和渠道事实必须来自各自角色，不能用一条履约事件冒充三方。

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

## 上线限制

现有通用金额 Job 的 DB reader 尚未完整携带 tenant/账期过滤，因此 `BENEFIT_CASH_3WAY` seed 保持 disabled，不能直接面向全租户定时运行。上线前必须完成 tenant-aware `RunKey/SourceReadContext`、源端窗口谓词、租户样本守恒和迟到数据重跑验证。非现金分类器已可验证规则，但 `ENTITLEMENT_FULFILLMENT` 的持久化批运行、运营报表和处置 UI 仍属于后续工程；在此之前只可作为 ODS/分类基线，不能宣称全自动闭环。
