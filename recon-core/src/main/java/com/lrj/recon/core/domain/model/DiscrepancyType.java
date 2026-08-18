package com.lrj.recon.core.domain.model;

/**
 * 机器可判的差异类型 (10 值) 及其优先级。
 *
 * <p>优先级 (设计 §9, 数值越小优先级越高, 一组只发一条主类型):
 * <pre>
 * BRIDGE_BROKEN &gt; CURRENCY_MISMATCH &gt; DUPLICATE/EXTRA &gt; GROUP_SUM_MISMATCH
 *               &gt; AMOUNT_MISMATCH &gt; STATUS_MISMATCH &gt; TIMING &gt; MISSING
 * </pre>
 * DUPLICATE 与 EXTRA 同级 (由 presence 天然互斥, 不会同组竞争)。
 * FX_RATE_DIFF 为阶段二留位, MVP 不判定, 给最低优先级 (永不被选中)。
 */
public enum DiscrepancyType {
    BRIDGE_BROKEN(0),
    CURRENCY_MISMATCH(1),
    DUPLICATE(2),
    EXTRA(2),
    GROUP_SUM_MISMATCH(3),
    AMOUNT_MISMATCH(4),
    STATUS_MISMATCH(5),
    TIMING(6),
    MISSING(7),
    /** 阶段二留位: 汇率差, MVP 不参与判定。 */
    FX_RATE_DIFF(Integer.MAX_VALUE);

    private final int precedence;

    DiscrepancyType(int precedence) {
        this.precedence = precedence;
    }

    /** 数值越小优先级越高。用于"同组只发一条主类型"的择优。 */
    public int precedence() {
        return precedence;
    }

    /** 是否为 MVP 会实际判定的类型 (排除 FX_RATE_DIFF 留位)。 */
    public boolean judgedInMvp() {
        return this != FX_RATE_DIFF;
    }
}
