package com.lrj.recon.scenario.dsl;

import com.lrj.recon.scenario.SegmentDef;
import com.lrj.recon.scenario.SpineBridgeKeyExtractor;

import java.util.List;

/**
 * 通用装配产物: 与硬编码 {@code MarketingThreeWayScenario} 同构 —— 顺序执行的段蓝图 + 共享桥接抽取器。
 * 组合根据此装配 load→matchEvaluate→report,无需再为每个新场景写 Java。
 */
public record AssembledScenario(String code, List<SegmentDef> segments, SpineBridgeKeyExtractor extractor) {

    public AssembledScenario {
        segments = List.copyOf(segments);
    }

    public SegmentDef segment(int index) {
        return segments.get(index);
    }
}
