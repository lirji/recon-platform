package com.lrj.recon.core.spi;

import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.SegmentSpec;

/**
 * 插件 2a) 键抽取 (桥接)。SEG1 取 marketingIssueId, SEG2 取 channelSerialNo; spine 侧双键都取。
 *
 * <p>MVP (M0) 只定义接口; SpineBridgeKeyExtractor 实现归 M4。
 */
public interface KeyExtractor {

    String extractorId();

    /** 抽取匹配键 (该侧无键 → {@code null})。 */
    MatchKey extract(ReconRecord record, SegmentSpec segment, int bucketCount);

    /** 抽取 1:N 聚合键。 */
    GroupKey groupKey(ReconRecord record, SegmentSpec segment);
}
