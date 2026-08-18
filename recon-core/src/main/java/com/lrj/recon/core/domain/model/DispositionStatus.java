package com.lrj.recon.core.domain.model;

/**
 * 人工处置状态 (设计 §5 discrepancy_disposition.status)。
 * 独立于 Run 生命周期, <b>永不被重跑删除</b> (ADR-7)。
 *
 * <p>{@link #STALE} 非人工动作产出, 而是<b>重跑收敛</b> (A1 决议②/③) 的系统态: 人工处置过、但重算后该
 * 机器差异已消失 (或因 type 变导致 fingerprint 变、旧处置悬空) → 自动关闭并标 STALE + 留审计。STALE 不再参与
 * 自动收敛；若相同差异再度出现，人工仍可重新 RESOLVE/CLOSE/SUPPRESS（处置时视同 OPEN）。
 */
public enum DispositionStatus {
    RESOLVED,
    CLOSED,
    SUPPRESSED,
    REOPENED,
    /** 重跑收敛自动关闭 (A1②/③): 处置过但重算后差异消失 / type 变致 fingerprint 悬空。 */
    STALE
}
