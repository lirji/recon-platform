package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.ConservationPartial;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.ReconReport;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.ClassifiedGroup;
import com.lrj.recon.core.domain.service.ConservationAccumulator;
import com.lrj.recon.core.domain.service.ConservationChecker;
import com.lrj.recon.core.domain.service.ConservationMerger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 验收: <b>单遍守恒 == 双遍守恒</b> (设计 §8 / 里程碑 M3)。
 *
 * <p>同一批分类组:
 * <ul>
 *   <li><b>双遍</b> (M2 基线): {@link ConservationChecker} 整段一次累计 → 报表;</li>
 *   <li><b>单遍</b> (M3): 把组<b>分散到多个 bucket</b>, 每 bucket 用独立 {@link ConservationAccumulator} 流式累计落
 *       {@link ConservationPartial}, 再由 {@link ConservationMerger} 跨 bucket 合并 → 报表。</li>
 * </ul>
 * 断言两者<b>逐字段相等</b> (每个 (segment,currency) 桶的 expected/matched/各差异列/residual/balanced 全等)。
 */
class ConservationSinglePassParityTest {

    private static final String RUN = "run-parity";
    private static final String SEG = "SEG";

    @Test
    void single_pass_bucketed_equals_double_pass_full_scan() {
        EvaluationContext ctx = ReconFixtures.plainContext();

        // 覆盖多类型 + 多币种 + 红蓝字 (与 ConservationCheckerTest / e2e 同构, 但规模更大以铺满多个 bucket)。
        List<ReconRecord> lefts = new ArrayList<>();
        List<ReconRecord> rights = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String k = "M" + i;                      // 干净匹配
            lefts.add(ReconFixtures.left(k, 100 + i));
            rights.add(ReconFixtures.right(k, 100 + i));
        }
        lefts.add(ReconFixtures.left("A1", 1000));   // AMOUNT_MISMATCH
        rights.add(ReconFixtures.right("A1", 900));
        lefts.add(ReconFixtures.left("MS1", 500));   // MISSING (仅左)
        rights.add(ReconFixtures.right("E1", 700));  // EXTRA (仅右)
        // GROUP_SUM_MISMATCH (红蓝字, 左净 300 != 右 500)
        lefts.add(ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "G1", "USD", 400, EntryType.ISSUE).build());
        lefts.add(ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "G1", "USD", -100, EntryType.REFUND).build());
        rights.add(ReconFixtures.right("G1", 500));
        // CURRENCY_MISMATCH (左 USD / 右 EUR → 跨两币桶)
        lefts.add(ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "C1", "USD", 250, EntryType.ISSUE).build());
        rights.add(ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "C1", "EUR", 250, EntryType.ISSUE).build());
        // 纯 EUR 干净匹配 (多币种)
        lefts.add(ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "U1", "EUR", 333, EntryType.ISSUE).build());
        rights.add(ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "U1", "EUR", 333, EntryType.ISSUE).build());

        List<ClassifiedGroup> classified = ReconFixtures.run(ctx, lefts, rights).classified();

        // ---- 双遍 (基线) ----
        Map<String, ReconReport> doublePass = index(new ConservationChecker().check(RUN, SEG, classified));

        // ---- 单遍: 把组分散到 7 个 bucket, 每 bucket 独立 accumulator → partials → merge ----
        int bucketCount = 7;
        List<ConservationAccumulator> perBucket = new ArrayList<>();
        for (int b = 0; b < bucketCount; b++) {
            perBucket.add(new ConservationAccumulator());
        }
        // 每个组整体落一个 bucket (不拆组), 模拟 bucket = hash(group_key)。
        for (int i = 0; i < classified.size(); i++) {
            perBucket.get(i % bucketCount).accept(classified.get(i));
        }
        List<ConservationPartial> partials = new ArrayList<>();
        for (int b = 0; b < bucketCount; b++) {
            partials.addAll(perBucket.get(b).toPartials(RUN, SEG, b, -1));
        }
        Map<String, ReconReport> singlePass = index(new ConservationMerger().merge(RUN, partials));

        // ---- 逐字段相等 ----
        assertThat(singlePass.keySet()).isEqualTo(doublePass.keySet());
        for (String ccy : doublePass.keySet()) {
            assertReportsEqual(singlePass.get(ccy), doublePass.get(ccy));
        }
        // 且确实非平凡: 覆盖到 USD + EUR 两桶且均 balanced。
        assertThat(doublePass.keySet()).containsExactlyInAnyOrder(ReconFixtures.USD, ReconFixtures.EUR);
        assertThat(doublePass.values()).allMatch(ReconReport::balanced);
    }

    private static void assertReportsEqual(ReconReport a, ReconReport b) {
        assertThat(a.currency()).isEqualTo(b.currency());
        assertThat(a.expectedTotalMinor()).isEqualTo(b.expectedTotalMinor());
        assertThat(a.matchedAmountMinor()).isEqualTo(b.matchedAmountMinor());
        assertThat(a.amountMismatchMinor()).isEqualTo(b.amountMismatchMinor());
        assertThat(a.missingMinor()).isEqualTo(b.missingMinor());
        assertThat(a.duplicateMinor()).isEqualTo(b.duplicateMinor());
        assertThat(a.extraMinor()).isEqualTo(b.extraMinor());
        assertThat(a.timingMinor()).isEqualTo(b.timingMinor());
        assertThat(a.statusMismatchMinor()).isEqualTo(b.statusMismatchMinor());
        assertThat(a.currencyMismatchMinor()).isEqualTo(b.currencyMismatchMinor());
        assertThat(a.groupSumMismatchMinor()).isEqualTo(b.groupSumMismatchMinor());
        assertThat(a.bridgeBrokenMinor()).isEqualTo(b.bridgeBrokenMinor());
        assertThat(a.rightSideTotalMinor()).isEqualTo(b.rightSideTotalMinor());
        assertThat(a.leftResidualMinor()).isEqualTo(b.leftResidualMinor());
        assertThat(a.rightResidualMinor()).isEqualTo(b.rightResidualMinor());
        assertThat(a.balanced()).isEqualTo(b.balanced());
    }

    private static Map<String, ReconReport> index(List<ReconReport> reports) {
        return reports.stream().collect(Collectors.toMap(ReconReport::currency, Function.identity()));
    }
}
