package com.lrj.recon.core.domain.model;

import java.util.List;

/**
 * 责任链一段的配置。营销三方 = [SEG1_MKT_ACCT, SEG2_ACCT_CHANNEL]。
 *
 * <p>{@code spineRole} 是桥接锚 (账务): SEG1 中 spine 在右侧、SEG2 中 spine 在左侧。
 * 缺失侧角色 == spineRole 时判 BRIDGE_BROKEN (优先于 MISSING), 断段由 {@code stageLabel} 归因。
 */
public record SegmentSpec(
        String segmentId,
        SourceRole leftRole,
        SourceRole rightRole,
        SourceRole spineRole,
        String stageLabel,      // "SEG1" | "SEG2"
        String extractorId,
        String strategyId,
        String evaluatorId,
        List<String> handlerIds) {

    public SegmentSpec {
        handlerIds = handlerIds == null ? List.of() : List.copyOf(handlerIds);
    }
}
