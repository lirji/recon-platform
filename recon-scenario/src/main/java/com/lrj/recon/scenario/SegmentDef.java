package com.lrj.recon.scenario;

import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.spi.SourceDescriptor;

import java.util.Objects;

/**
 * 责任链一段的完整装配蓝图 (M4, recon-scenario): 段规约 + 左右数据源描述符 + 判差规则。
 *
 * <p>{@link SegmentSpec} 携带段内角色 (left/right/spine)、断段标签、抽取器/策略/判差器 id;
 * {@code leftSource}/{@code rightSource} 是该段两侧的源定位 (spine 账务在两段分别作右/左侧, 用不同描述符投影不同键列)。
 * 组合根 (recon-batch) 据本蓝图逐段装配 load→matchEvaluate→report 步骤。纯数据, 零框架。
 */
public record SegmentDef(
        SegmentSpec spec,
        SourceDescriptor leftSource,
        SourceDescriptor rightSource,
        DiscrepancyRule rule) {

    public SegmentDef {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(leftSource, "leftSource");
        Objects.requireNonNull(rightSource, "rightSource");
        Objects.requireNonNull(rule, "rule");
    }

    public String segmentId() {
        return spec.segmentId();
    }
}
