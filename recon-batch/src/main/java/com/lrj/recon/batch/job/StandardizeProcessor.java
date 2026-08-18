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
 * 用 {@link Bucketing} 算 bucket (桶键 = group_key, 修补①), 并 fail-fast 校验<b>放宽版 refine 不变式</b>
 * (M4: 允许 match_key != group_key, 但带 match_key 必须有非空 group_key 以分桶), 产出可落 staging 的最终记录。
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

        // 修补①(M4 放宽): 装载期 refine 不变式硬校验 (放宽版, O(1) 无状态): 带 match_key 必须有非空 group_key
        // 以分桶 (允许 match != group, 如 SEG1 营销发放ID→发放单号 1:N); 违背即 fail-fast。
        // ⚠️ 局限 (KI-6): 本处<b>不</b>校验"同一 match_key 只属唯一 group_key"的<b>数据函数性</b> —— 那需跨记录全表
        // match→group 映射, 千万级热路径不可建; KeySpec 装配期只校验键<b>字段名</b>非空、不校验数据值的函数性。
        // 数据函数性靠<b>上游数据质量</b>保证: 若脏数据违反 (同一 match_key 两侧挂不同 group_key), 两侧会落不同桶 →
        // 产<b>假 BRIDGE_BROKEN + 假 EXTRA</b>, 且左右额分别独立入账使守恒仍闭合、抓不到。可选离线/装配期<b>抽样</b>
        // 预校验见 {@link com.lrj.recon.core.domain.service.Bucketing#assertRefineFunction} (需跨记录状态, 不进热路径)。
        Bucketing.assertRefine(matchKeyValue, groupKeyValue);

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
