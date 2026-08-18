package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Presence;
import com.lrj.recon.core.domain.service.ToleranceEvaluator;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ToleranceEvaluator} 单测 (M4): 金额双阈值容差 (绝对 + 比例 bps) 对 AMOUNT_MISMATCH 的抑制。
 * 覆盖: 容差内不产差 / 容差外产 AMOUNT_MISMATCH / 恰好等于阈值边界 (<=, 含) / 比例阈值 / 双阈值取更宽者。
 */
class ToleranceEvaluatorTest {

    private final DiscrepancyEvaluator tolerance = new ToleranceEvaluator();

    private static MatchGroup oneToOne(long left, long right) {
        // 1:1 组: 左 left / 右 right (同键同币), 用于 AMOUNT_MISMATCH 判定。
        EvaluationContext ctx = ReconFixtures.plainContext();
        ReconFixtures.Result r = ReconFixtures.run(ctx,
                List.of(ReconFixtures.left("K1", left)),
                List.of(ReconFixtures.right("K1", right)));
        return r.groups().get(0);
    }

    private static DiscrepancyRule absRule(long absMinor) {
        return DiscrepancyRule.builder()
                .evaluatorType(EvaluatorType.TOLERANCE)
                .absToleranceMinor(absMinor)
                .ratioToleranceBps(0)
                .build();
    }

    private static DiscrepancyRule ratioRule(int bps) {
        return DiscrepancyRule.builder()
                .evaluatorType(EvaluatorType.TOLERANCE)
                .absToleranceMinor(0)
                .ratioToleranceBps(bps)
                .build();
    }

    @Test
    void withinAbsoluteToleranceProducesNoDiscrepancy() {
        // |1000-995| = 5 <= 10 → 容差内, 不产差
        MatchGroup g = oneToOne(1000, 995);
        assertThat(tolerance.evaluate(g, absRule(10), ReconFixtures.plainContext())).isEmpty();
    }

    @Test
    void outsideAbsoluteToleranceProducesAmountMismatch() {
        // |1000-980| = 20 > 10 → 容差外, 产 AMOUNT_MISMATCH
        MatchGroup g = oneToOne(1000, 980);
        List<Discrepancy> out = tolerance.evaluate(g, absRule(10), ReconFixtures.plainContext());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
    }

    @Test
    void exactlyAtAbsoluteThresholdIsWithinTolerance() {
        // |1000-990| = 10 == 阈值 → 边界含 (<=), 不产差
        MatchGroup g = oneToOne(1000, 990);
        assertThat(tolerance.evaluate(g, absRule(10), ReconFixtures.plainContext())).isEmpty();
    }

    @Test
    void oneMinorUnitPastThresholdProducesMismatch() {
        // |1000-989| = 11 > 10 → 恰超一分, 产差 (边界明确)
        MatchGroup g = oneToOne(1000, 989);
        assertThat(tolerance.evaluate(g, absRule(10), ReconFixtures.plainContext())).hasSize(1);
    }

    @Test
    void ratioToleranceUsesBasisPointsOfExpected() {
        // expected=1_000_000, ratio=50bps=0.5% → 阈值 5000. |Δ|=4000 <= 5000 → 容差内
        MatchGroup within = oneToOne(1_000_000, 996_000);
        assertThat(tolerance.evaluate(within, ratioRule(50), ReconFixtures.plainContext())).isEmpty();
        // |Δ|=6000 > 5000 → 容差外
        MatchGroup outside = oneToOne(1_000_000, 994_000);
        assertThat(tolerance.evaluate(outside, ratioRule(50), ReconFixtures.plainContext())).hasSize(1);
    }

    @Test
    void ratioToleranceExactBoundaryIsWithin() {
        // expected=1_000_000, ratio=50bps → 阈值恰 5000; |Δ|=5000 → <= 边界含, 不产差
        MatchGroup g = oneToOne(1_000_000, 995_000);
        assertThat(tolerance.evaluate(g, ratioRule(50), ReconFixtures.plainContext())).isEmpty();
    }

    @Test
    void dualThresholdTakesWiderOne() {
        // abs=3 (窄) + ratio=100bps=1% of 1000 = 10 (宽); |Δ|=8 → 超 abs 但在 ratio 内 → 取更宽者, 不产差
        DiscrepancyRule dual = DiscrepancyRule.builder()
                .evaluatorType(EvaluatorType.TOLERANCE).absToleranceMinor(3).ratioToleranceBps(100).build();
        MatchGroup g = oneToOne(1000, 992);
        assertThat(tolerance.evaluate(g, dual, ReconFixtures.plainContext())).isEmpty();
    }

    @Test
    void zeroTolerancesBehaveLikeExact() {
        // 阈值全 0 → 任何差都产 AMOUNT_MISMATCH (退化为精确比较)
        DiscrepancyRule zero = DiscrepancyRule.builder()
                .evaluatorType(EvaluatorType.TOLERANCE).absToleranceMinor(0).ratioToleranceBps(0).build();
        MatchGroup g = oneToOne(1000, 999);
        assertThat(tolerance.evaluate(g, zero, ReconFixtures.plainContext())).hasSize(1);
    }

    @Test
    void longMinValueExpectedFailsFastNotSilentlySwallowed() {
        // 修复 C: expected=Long.MIN_VALUE / actual=0 → |Δ| = |Long.MIN_VALUE|. 裸 Math.abs 会回绕为负,
        // 经绝对阈值分支 (负 delta <= 任意正阈值 恒真) 静默吞掉真 AMOUNT_MISMATCH; 改 Math.absExact 后溢出 fail-fast。
        MatchGroup g = MatchGroup.builder()
                .matchKey(MatchKey.of("key", "K1", 0))
                .groupKey(GroupKey.of("key", "K1"))
                .presence(Presence.BOTH)
                .leftCurrency(ReconFixtures.USD).rightCurrency(ReconFixtures.USD)
                .sumSignedLeftMinor(Long.MIN_VALUE).sumSignedRightMinor(0L)
                .countLeft(1).countRight(1).duplicate(false)
                .build();
        // 行为明确: 溢出 fail-fast (ArithmeticException), 绝不被容差静默吞掉。
        assertThatThrownBy(() -> tolerance.evaluate(g, absRule(10), ReconFixtures.plainContext()))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void toleranceOnlyRelaxesAmountMismatchNotOtherTypes() {
        // MISSING (仅左) 不受金额容差影响: 即使 absTolerance 很大, 仍产 MISSING
        EvaluationContext ctx = ReconFixtures.plainContext();
        ReconFixtures.Result r = ReconFixtures.run(ctx,
                List.of(ReconFixtures.left("KM", 500)), List.of());
        MatchGroup missingGroup = r.groups().get(0);
        List<Discrepancy> out = tolerance.evaluate(missingGroup, absRule(100_000), ctx);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo(DiscrepancyType.MISSING);
    }
}
