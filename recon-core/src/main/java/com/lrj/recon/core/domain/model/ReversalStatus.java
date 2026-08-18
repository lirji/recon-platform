package com.lrj.recon.core.domain.model;

/**
 * 冲正建议状态 (设计 §5 reversal_suggestion.status)。
 * <b>仅建议, 无资金动作</b>; 幂等键唯一, 永不被重跑删除 (ADR-7)。
 */
public enum ReversalStatus {
    SUGGESTED,
    CONFIRMED,
    DISCARDED
}
