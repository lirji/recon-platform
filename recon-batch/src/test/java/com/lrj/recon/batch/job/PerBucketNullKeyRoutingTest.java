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
import com.lrj.recon.core.spi.RecordCursor;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #8 回归 (per-bucket 游标可移植 NULL 排序 + null 键路由出 join): 单桶内混<b>非空键</b>与<b>null match_key</b>
 * 记录时, per-bucket 游标 {@code cursor(run,seg,side,bucket)} 用 {@code ORDER BY (match_key IS NULL), match_key}
 * 把 null 键排最后, {@link BucketGroupReader}/{@link SegmentGroupCursor} 据此把 null 键逐条路由为<b>单边组</b>,
 * 绝不喂给 {@link com.lrj.recon.core.domain.service.SortMergeJoiner} (joiner 拒 null 键会抛异常)。
 *
 * <p>覆盖原纯 {@code ORDER BY match_key} 在 MySQL(NULLS-FIRST) 下把 null 插到非空键簇前、阻断 sort-merge 的地雷
 * (H2 亦按可移植表达式验证顺序/路由正确)。
 */
class PerBucketNullKeyRoutingTest extends AbstractReconJobIT {

    private static final String SEG = "SEG1_MKT_ACCT";
    private static final String RUN = "run-perbucket-null";

    @Autowired ReconRecordRepository records;

    @Test
    void perBucketCursorRoutesNullKeysOutOfJoinAndKeepsKeyedMergeCorrect() {
        // bucketCount=1 → 全落 bucket 0。混: 键 A (两侧同额 100 干净), 键 B (500/400 AMOUNT_MISMATCH),
        // null 键 LEFT(group NL, 700) 与 null 键 RIGHT(group NR, 800)。
        List<ReconRecord> seed = new ArrayList<>();
        seed.add(rec(Side.LEFT, "A", "A", 100, "la"));
        seed.add(rec(Side.RIGHT, "A", "A", 100, "ra"));
        seed.add(rec(Side.LEFT, "B", "B", 500, "lb"));
        seed.add(rec(Side.RIGHT, "B", "B", 400, "rb"));
        seed.add(rec(Side.LEFT, null, "NL", 700, "lnull"));
        seed.add(rec(Side.RIGHT, null, "NR", 800, "rnull"));
        records.batchInsert(seed);

        // 先验证 per-bucket 游标本身把 null 键排在最后 (可移植 NULL 排序)
        List<String> leftOrder = new ArrayList<>();
        try (RecordCursor c = records.cursor(RUN, SEG, Side.LEFT, 0)) {
            ReconRecord r;
            while ((r = c.next()) != null) {
                leftOrder.add(r.matchKey() == null ? "<null>" : r.matchKey().value());
            }
        }
        assertThat(leftOrder).containsExactly("A", "B", "<null>"); // 非空键升序在前, null 最后

        // 用 BucketGroupReader (per-bucket 路径) 逐组读 —— 若 null 键被喂进 joiner, read() 会抛异常
        List<MatchGroup> groups = new ArrayList<>();
        BucketGroupReader reader = new BucketGroupReader(records, RUN, SEG, 0, -1, 1);
        reader.open(new ExecutionContext());
        try {
            MatchGroup g;
            while ((g = reader.read()) != null) {
                groups.add(g);
            }
        } finally {
            reader.close();
        }

        // null 键 → 两条单边组 (未进 join)
        List<MatchGroup> nullGroups = groups.stream().filter(mg -> mg.matchKey() == null).toList();
        assertThat(nullGroups).hasSize(2);
        assertThat(nullGroups).extracting(MatchGroup::presence)
                .containsExactlyInAnyOrder(Presence.LEFT_ONLY, Presence.RIGHT_ONLY);

        // 键 A 干净匹配 (两侧各 1 条), 键 B 两侧配上 (待判 AMOUNT_MISMATCH)
        MatchGroup a = keyed(groups, "A");
        assertThat(a.presence()).isEqualTo(Presence.BOTH);
        assertThat(a.countLeft()).isEqualTo(1);
        assertThat(a.countRight()).isEqualTo(1);
        MatchGroup b = keyed(groups, "B");
        assertThat(b.presence()).isEqualTo(Presence.BOTH);

        // 分类: null-左→MISSING, null-右→EXTRA, B→AMOUNT_MISMATCH (证明路由与归并都对)
        DiscrepancyClassifier classifier = new DiscrepancyClassifier();
        EvaluationContext ctx = EvaluationContext.builder()
                .runId(RUN).scenarioCode(SCENARIO).accountingPeriod(PERIOD).segmentId(SEG)
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(null)
                .matchWindowFrom(WINDOW_FROM).matchWindowTo(WINDOW_TO)
                .build();
        List<DiscrepancyType> types = groups.stream()
                .map(g -> classifier.classify(g, ctx)).filter(d -> d != null)
                .map(Discrepancy::type).toList();
        assertThat(types).containsExactlyInAnyOrder(
                DiscrepancyType.AMOUNT_MISMATCH, DiscrepancyType.MISSING, DiscrepancyType.EXTRA);
    }

    private static MatchGroup keyed(List<MatchGroup> groups, String key) {
        return groups.stream().filter(mg -> mg.matchKey() != null && mg.matchKey().value().equals(key))
                .findFirst().orElseThrow();
    }

    private ReconRecord rec(Side side, String matchKeyValue, String groupKeyValue, long amount, String recId) {
        int bucket = Bucketing.bucketOf(groupKeyValue, 1); // bucketCount=1 → 全 bucket 0
        MatchKey mk = matchKeyValue == null ? null : MatchKey.of("k", matchKeyValue, bucket);
        return ReconRecord.builder()
                .recordId(RUN + ":" + side + ":" + recId)
                .runId(RUN).segmentId(SEG).side(side)
                .sourceRole(side == Side.LEFT ? SourceRole.MARKETING : SourceRole.ACCOUNTING)
                .matchKey(mk)
                .groupKey(GroupKey.of("k", groupKeyValue))
                .bucket(bucket)
                .money(Money.of("USD", amount))
                .entryType(EntryType.ISSUE)
                .bizTime(BIZ)
                .rawRef("t:" + recId)
                .build();
    }
}
