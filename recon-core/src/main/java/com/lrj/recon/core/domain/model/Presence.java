package com.lrj.recon.core.domain.model;

/** 一个匹配组在左右两路的出现情况。 */
public enum Presence {
    /** 左右都有记录。 */
    BOTH,
    /** 只有左侧有记录 (右侧缺失)。 */
    LEFT_ONLY,
    /** 只有右侧有记录 (左侧缺失)。 */
    RIGHT_ONLY
}
