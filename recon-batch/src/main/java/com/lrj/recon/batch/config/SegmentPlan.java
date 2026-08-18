package com.lrj.recon.batch.config;

import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.spi.KeyExtractor;
import com.lrj.recon.core.spi.SourceDescriptor;

/**
 * 一段对账的装配蓝图 (M2 单段): 段规约 + 左右数据源描述符 + 键抽取器 + 判差规则。
 *
 * <p>M2 只装配一段 (marketing↔accounting), 两段桥接责任链 (SEG1+SEG2) 归 M4。
 */
public record SegmentPlan(
        SegmentSpec spec,
        SourceDescriptor leftSource,
        SourceDescriptor rightSource,
        KeyExtractor extractor,
        DiscrepancyRule rule) {

    public String segmentId() {
        return spec.segmentId();
    }
}
