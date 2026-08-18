package com.lrj.recon.core.domain.model;

/**
 * 人工处置状态 (设计 §5 discrepancy_disposition.status)。
 * 独立于 Run 生命周期, <b>永不被重跑删除</b> (ADR-7)。
 */
public enum DispositionStatus {
    RESOLVED,
    CLOSED,
    SUPPRESSED,
    REOPENED
}
