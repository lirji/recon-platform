package com.lrj.recon.batch.job;

import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.domain.service.Bucketing;
import com.lrj.recon.core.spi.KeyExtractor;

/**
 * M2 最小键抽取器 (IDENTITY refine): {@code match_key == group_key} (设计修补①)。
 *
 * <p>MVP 两段本就满足 match_key == group_key (SEG1 都为 marketingIssueId, SEG2 都为 channelSerialNo),
 * 故 M2 walking-skeleton 用本实现把 marketing↔accounting 单段跑通。真正的桥接双键抽取
 * {@code SpineBridgeKeyExtractor} (spine 侧取双键, 该侧无键→null) 归 M4。
 *
 * <p>group_key 取源适配器已映射的 {@link ReconRecord#groupKey()}; match_key 与之相等,
 * bucket 由 {@link Bucketing#bucketOf(String, int)} 从 group_key 算出 (桶键 = group_key)。
 */
public final class IdentityKeyExtractor implements KeyExtractor {

    public static final String ID = "identity";

    @Override
    public String extractorId() {
        return ID;
    }

    @Override
    public MatchKey extract(ReconRecord record, SegmentSpec segment, int bucketCount) {
        GroupKey gk = groupKey(record, segment);
        if (gk == null) {
            return null; // 该侧无键 (M2 identity 段下不出现; 保留 SPI 契约)
        }
        int bucket = Bucketing.bucketOf(gk.value(), bucketCount);
        return MatchKey.of(gk.fieldName(), gk.value(), bucket);
    }

    @Override
    public GroupKey groupKey(ReconRecord record, SegmentSpec segment) {
        return record.groupKey();
    }
}
