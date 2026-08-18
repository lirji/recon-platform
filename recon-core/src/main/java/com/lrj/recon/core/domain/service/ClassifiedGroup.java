package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.MatchGroup;

/**
 * 一个匹配组 + 其分类结果的配对, 供 {@link ConservationChecker} 构造性守恒使用。
 * {@code type == null} 表示该组干净匹配。
 */
public record ClassifiedGroup(MatchGroup group, DiscrepancyType type) {

    public static ClassifiedGroup matched(MatchGroup group) {
        return new ClassifiedGroup(group, null);
    }

    public static ClassifiedGroup of(MatchGroup group, DiscrepancyType type) {
        return new ClassifiedGroup(group, type);
    }

    public boolean isMatched() {
        return type == null;
    }
}
