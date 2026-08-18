package com.lrj.recon.batch.job;

import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.domain.service.Bucketing;
import com.lrj.recon.core.domain.service.KeyNormalizer;
import com.lrj.recon.core.spi.KeyExtractor;
import org.springframework.batch.item.ItemProcessor;

/**
 * Step1 loadStep 的 processor (设计 §6): 对源适配器产出的原始记录做<b>标准化收口</b> ——
 * 用 {@link KeyExtractor} 算 match_key / group_key, 用 {@link KeyNormalizer} 去尾随空白 (遗留②/#2),
 * 用 {@link Bucketing} 算 bucket (桶键 = group_key, 修补①), 并 fail-fast 校验 IDENTITY refine 不变式
 * (match_key == group_key), 产出可落 staging 的最终记录。
 *
 * <p><b>#2 尾随空白规范化</b>: 两侧记录都经过本 processor, 是唯一的标准化收口点。此处对 match_key / group_key
 * 统一 {@code stripTrailing}, 落库值无尾随空白, 从根上消除 MySQL(PAD SPACE) 与 Java(no-pad)/PG 对键相等性的
 * 判断分歧 —— 否则一侧 {@code 'K1'}、一侧 {@code 'K1 '} 会被 DB 视为相等、被 Java 归并视为不等, 产生假 MISSING/EXTRA。
 */
public class StandardizeProcessor implements ItemProcessor<ReconRecord, ReconRecord> {

    private final KeyExtractor extractor;
    private final SegmentSpec segment;
    private final int bucketCount;

    public StandardizeProcessor(KeyExtractor extractor, SegmentSpec segment, int bucketCount) {
        this.extractor = extractor;
        this.segment = segment;
        this.bucketCount = bucketCount;
    }

    @Override
    public ReconRecord process(ReconRecord raw) {
        GroupKey groupKey = extractor.groupKey(raw, segment);
        MatchKey matchKey = extractor.extract(raw, segment, bucketCount);

        // #2: 去尾随空白后再定身份/分桶 (消除 PAD SPACE 差异); null 保持 null。
        String matchKeyValue = KeyNormalizer.normalizeTrailing(matchKey == null ? null : matchKey.value());
        String groupKeyValue = KeyNormalizer.normalizeTrailing(groupKey == null ? null : groupKey.value());

        // 修补①: 装载期 refine 不变式硬校验 (match_key 非空则必 == group_key), 违背即 fail-fast。
        Bucketing.assertIdentityRefine(matchKeyValue, groupKeyValue);

        int bucket = Bucketing.bucketOf(groupKeyValue, bucketCount);
        GroupKey normGroupKey = groupKey == null ? null : GroupKey.of(groupKey.fieldName(), groupKeyValue);
        MatchKey normMatchKey = matchKeyValue == null ? null : MatchKey.of(
                matchKey.fieldName(), matchKeyValue, bucket);
        return raw.toBuilder()
                .groupKey(normGroupKey)
                .matchKey(normMatchKey)
                .bucket(bucket)
                .build();
    }
}
