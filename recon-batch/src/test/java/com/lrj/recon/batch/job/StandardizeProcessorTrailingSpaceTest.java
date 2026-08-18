package com.lrj.recon.batch.job;

import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.SegmentSpec;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.Bucketing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2 单测 (标准化收口): 一侧键带尾随空格、一侧不带, 经 {@link StandardizeProcessor} 规范化后归一到<b>同键同桶</b>,
 * 从而在 sort-merge 归并时会配上 —— 不再产生假 MISSING/EXTRA。覆盖 MySQL(PAD SPACE) vs Java/PG(no-pad) 的坑。
 */
class StandardizeProcessorTrailingSpaceTest {

    private static final SegmentSpec SEG = new SegmentSpec(
            "SEG1_MKT_ACCT", SourceRole.MARKETING, SourceRole.ACCOUNTING, null, "SEG1",
            IdentityKeyExtractor.ID, "grp", "exact", List.of());

    private final StandardizeProcessor processor =
            new StandardizeProcessor(new IdentityKeyExtractor(), SEG, 8);

    @Test
    void trailingSpaceKeyNormalizesToSameKeyAndBucketAsPlainKey() {
        ReconRecord withSpace = processor.process(raw(Side.LEFT, "K1 ", "l-space"));   // 尾随空格
        ReconRecord plain = processor.process(raw(Side.RIGHT, "K1", "r-plain"));       // 无尾随空格

        // 键值归一
        assertThat(withSpace.matchKey().value()).isEqualTo("K1");
        assertThat(withSpace.groupKey().value()).isEqualTo("K1");
        assertThat(plain.matchKey().value()).isEqualTo("K1");

        // 落同桶 → sort-merge 会归并到同一键簇 (否则跨桶分裂 / 假 MISSING/EXTRA)
        assertThat(withSpace.bucket()).isEqualTo(plain.bucket());
        assertThat(withSpace.bucket()).isEqualTo(Bucketing.bucketOf("K1", 8));

        // 键相等性: 两侧规范化后 MatchKey 相等 (compareTo == 0), sort-merge 判为同键
        assertThat(withSpace.matchKey().compareTo(plain.matchKey())).isZero();
    }

    private static ReconRecord raw(Side side, String groupKeyValue, String recId) {
        return ReconRecord.builder()
                .recordId(recId)
                .runId("run-x").segmentId(SEG.segmentId()).side(side)
                .sourceRole(side == Side.LEFT ? SourceRole.MARKETING : SourceRole.ACCOUNTING)
                .matchKey(null)                              // 源无 match_key, 由 processor 抽
                .groupKey(GroupKey.of("issueId", groupKeyValue))
                .bucket(0)
                .money(Money.of("USD", 100))
                .entryType(EntryType.ISSUE)
                .bizTime(Instant.parse("2026-08-17T10:00:00Z"))
                .rawRef("t:" + recId)
                .build();
    }
}
