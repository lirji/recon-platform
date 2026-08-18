package com.lrj.recon.core.domain.model;

/**
 * Run 生命周期状态 (设计 §6 各 Step 推进)。
 *
 * <pre>
 * CREATED --start--> LOADING --toMatching--> MATCHING --complete--------> COMPLETED
 *                                                     \--markImbalance--> REPORT_IMBALANCE
 * 任意非终态 --fail--> FAILED
 * </pre>
 * 终态: COMPLETED / REPORT_IMBALANCE / FAILED。
 */
public enum ReconRunStatus {
    /** 已 claim (INSERT 命中 uk_run), 尚未开始装载。 */
    CREATED,
    /** 装载 staging (Step1 loadStep)。 */
    LOADING,
    /** 匹配 + 判差 (Step2 matchEvaluateStep)。 */
    MATCHING,
    /** 守恒闭合, 正常完成。 */
    COMPLETED,
    /** 双向守恒不闭合 (left/right residual ≠ 0)。 */
    REPORT_IMBALANCE,
    /** 执行失败。 */
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == REPORT_IMBALANCE || this == FAILED;
    }
}
