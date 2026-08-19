package com.lrj.recon.core.domain.model;

/**
 * 冲正建议状态 (设计 §5 reversal_suggestion.status)。幂等键唯一, 永不被重跑删除 (ADR-7)。
 *
 * <p>生命周期: {@code SUGGESTED}(机器建议) → {@code CONFIRMED}/{@code DISCARDED}(B5 人工审批) →
 * {@code EXECUTED}/{@code EXECUTION_FAILED}(B3 冲正执行)。<b>SUGGESTED/CONFIRMED 阶段无资金动作</b>;
 * 资金动作只在 CONFIRMED→EXECUTED 由 {@code ReversalExecutor} 执行(生产接真实清结算适配器)。
 */
public enum ReversalStatus {
    SUGGESTED,
    CONFIRMED,
    DISCARDED,
    EXECUTED,
    EXECUTION_FAILED
}
