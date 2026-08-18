package com.lrj.recon.core.domain.model;

/**
 * 处置/处理动作审计类型 (设计 §5 discrepancy_action.action_type)。
 *
 * <p>覆盖自动处理链 (LEDGER / REVERSAL_SUGGESTION / ALERT) 与人工核销 (MANUAL_*) 及重跑收敛 (STALE_CLOSE)。
 * 每条审计按 {@code idempotency_key} 幂等 ({@code uk_action}), 重复触发不重复留痕。列宽 VARCHAR(24), 所有取值不超长。
 */
public enum DiscrepancyActionType {
    /** 差异入台账 (机器判差已由 writer upsert; 本审计记录处理链已受理该差异)。 */
    LEDGER,
    /** 生成冲正建议 (无资金动作)。 */
    REVERSAL_SUGGESTION,
    /** 告警入 outbox (外部副作用, 批后中继投递)。 */
    ALERT,
    MANUAL_RESOLVE,
    MANUAL_CLOSE,
    MANUAL_SUPPRESS,
    MANUAL_REOPEN,
    /** 重跑收敛: 处置过但重算后差异消失 / type 变致 fingerprint 悬空 → 自动关闭 (A1②/③)。 */
    STALE_CLOSE
}
