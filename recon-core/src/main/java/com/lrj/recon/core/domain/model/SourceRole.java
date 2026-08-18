package com.lrj.recon.core.domain.model;

/**
 * 记录来源在三方对账中的业务角色。营销三方 = 营销发钱、账务、渠道。
 * 账务 (ACCOUNTING) 是桥接 spine: SEG1 中作右侧、SEG2 中作左侧。
 */
public enum SourceRole {
    MARKETING,
    ACCOUNTING,
    CHANNEL
}
