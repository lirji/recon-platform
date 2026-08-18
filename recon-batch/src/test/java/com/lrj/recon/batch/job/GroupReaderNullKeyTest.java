package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.Presence;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.Bucketing;
import com.lrj.recon.core.domain.service.DiscrepancyClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 隐患① 回归: {@link GroupReader}/{@link SegmentGroupCursor} 以"整组 = 一 item"发射, 同组不被切断;
 * null match_key 记录<b>不进 join</b> (M0 SortMergeJoiner 拒 null 键会抛异常), 被路由为单边组
 * (LEFT_ONLY→MISSING / RIGHT_ONLY→EXTRA)。跨方言 NULL 排序由 {@code ORDER BY (match_key IS NULL), match_key} 兜底。
 */
class GroupReaderNullKeyTest extends AbstractReconJobIT {

    @Autowired ReconRecordRepository records;

    private static final String SEG = "SEG1_MKT_ACCT";
    private static final String RUN = "run-groupreader";

    @Test
    void wholeGroupIsOneItemAndNullKeysRoutedOutOfJoin() {
        List<ReconRecord> seed = new ArrayList<>();
        // 组 A (1:N, 键 "A"): 左 3 条 (100+200+300=600), 右 1 条 (600) → 整组一 item, countLeft=3
        seed.add(rec(Side.LEFT, "A", "A", 100, "ISSUE", "la1"));
        seed.add(rec(Side.LEFT, "A", "A", 200, "ISSUE", "la2"));
        seed.add(rec(Side.LEFT, "A", "A", 300, "ISSUE", "la3"));
        seed.add(rec(Side.RIGHT, "A", "A", 600, "ISSUE", "ra1"));
        // 组 B (1:1, 键 "B"): 左 500 / 右 400 → AMOUNT_MISMATCH
        seed.add(rec(Side.LEFT, "B", "B", 500, "ISSUE", "lb1"));
        seed.add(rec(Side.RIGHT, "B", "B", 400, "ISSUE", "rb1"));
        // null match_key: 左 (group NL) 700 → 路由为单边 → MISSING; 右 (group NR) 800 → EXTRA
        seed.add(rec(Side.LEFT, null, "NL", 700, "ISSUE", "lnull"));
        seed.add(rec(Side.RIGHT, null, "NR", 800, "ISSUE", "rnull"));
        records.batchInsert(seed);

        // 用 GroupReader (ItemStream 契约) 逐组读出 —— 若 null 键被喂进 joiner, 这里会抛异常
        List<MatchGroup> groups = new ArrayList<>();
        GroupReader reader = new GroupReader(records, RUN, SEG);
        reader.open(new org.springframework.batch.item.ExecutionContext());
        try {
            MatchGroup g;
            while ((g = reader.read()) != null) {
                groups.add(g);
            }
        } finally {
            reader.close();
        }

        // 组 A: 单个 item 承载整组 (未被切成两 item), 左 3 条右 1 条
        List<MatchGroup> groupA = groups.stream().filter(mg -> hasKey(mg, "A")).toList();
        assertThat(groupA).hasSize(1);
        assertThat(groupA.get(0).countLeft()).isEqualTo(3);
        assertThat(groupA.get(0).countRight()).isEqualTo(1);
        assertThat(groupA.get(0).sumSignedLeftMinor()).isEqualTo(600L);

        // null 键组: 两条单边组, match_key 均为 null (证明未进 join, 被路由为单边)
        List<MatchGroup> nullGroups = groups.stream().filter(mg -> mg.matchKey() == null).toList();
        assertThat(nullGroups).hasSize(2);
        assertThat(nullGroups).extracting(MatchGroup::presence)
                .containsExactlyInAnyOrder(Presence.LEFT_ONLY, Presence.RIGHT_ONLY);

        // 分类: null-左 → MISSING, null-右 → EXTRA, 组 B → AMOUNT_MISMATCH
        DiscrepancyClassifier classifier = new DiscrepancyClassifier();
        EvaluationContext ctx = EvaluationContext.builder()
                .runId(RUN).scenarioCode(SCENARIO).accountingPeriod(PERIOD).segmentId(SEG)
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(null)
                .matchWindowFrom(WINDOW_FROM).matchWindowTo(WINDOW_TO)
                .build();

        List<DiscrepancyType> types = groups.stream()
                .map(g -> classifier.classify(g, ctx))
                .filter(d -> d != null)
                .map(Discrepancy::type)
                .toList();
        assertThat(types).containsExactlyInAnyOrder(
                DiscrepancyType.AMOUNT_MISMATCH,  // 组 B
                DiscrepancyType.MISSING,          // null-左
                DiscrepancyType.EXTRA);           // null-右
    }

    private static boolean hasKey(MatchGroup mg, String key) {
        return mg.matchKey() != null && mg.matchKey().value().equals(key);
    }

    private ReconRecord rec(Side side, String matchKeyValue, String groupKeyValue, long amount,
                            String entryType, String recIdSuffix) {
        int bucket = Bucketing.bucketOf(groupKeyValue, BUCKET_COUNT);
        MatchKey mk = matchKeyValue == null ? null : MatchKey.of("k", matchKeyValue, bucket);
        return ReconRecord.builder()
                .recordId(RUN + ":" + side + ":" + recIdSuffix)
                .runId(RUN).segmentId(SEG).side(side)
                .sourceRole(side == Side.LEFT ? SourceRole.MARKETING : SourceRole.ACCOUNTING)
                .matchKey(mk)
                .groupKey(GroupKey.of("k", groupKeyValue))
                .bucket(bucket)
                .money(Money.of("USD", amount))
                .entryType(EntryType.valueOf(entryType))
                .bizTime(BIZ)
                .rawRef("t:" + recIdSuffix)
                .build();
    }
}
