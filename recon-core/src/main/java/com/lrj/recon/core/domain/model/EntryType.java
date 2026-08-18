package com.lrj.recon.core.domain.model;

/**
 * 分录类型 (红蓝字)。符号已经体现在 {@link Money#amountMinor()} 中 (REFUND/REVERSAL 通常为负额),
 * 本枚举只做血缘/展示, 不参与二次定符号, 避免重复取反。
 */
public enum EntryType {
    /** 发放/正向记账 (蓝字, 通常正额)。 */
    ISSUE,
    /** 退款 (红字, 通常负额)。 */
    REFUND,
    /** 冲正 (红字, 通常负额)。 */
    REVERSAL
}
