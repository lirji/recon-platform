package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Presence;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.DiscrepancyClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6 · 跨币汇率判定:两侧折算基准额可用时,容差内视为匹配、超容差 FX_RATE_DIFF;无基准额退回 CURRENCY_MISMATCH。
 */
class FxRateDiffClassifierTest {

    private final DiscrepancyClassifier classifier = new DiscrepancyClassifier();

    private static MatchGroup crossCurrency(Long leftBase, Long rightBase) {
        MatchGroup.Builder b = MatchGroup.builder()
                .matchKey(MatchKey.of("key", "k", 0))
                .groupKey(GroupKey.of("key", "k"))
                .presence(Presence.BOTH)
                .countLeft(1).countRight(1)
                .leftCurrency("USD").rightCurrency("EUR")
                .sumSignedLeftMinor(1000).sumSignedRightMinor(900)
                .leftSampleRawRef("l:1").rightSampleRawRef("r:1");
        if (leftBase != null) b.leftBaseMinor(leftBase);
        if (rightBase != null) b.rightBaseMinor(rightBase);
        return b.build();
    }

    private static EvaluationContext ctx(long fxTolerance) {
        return EvaluationContext.builder()
                .runId("run").scenarioCode("scn").accountingPeriod("2026-08-17").segmentId("SEG")
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(null)
                .fxToleranceMinor(fxTolerance)
                .build();
    }

    @Test
    void base_within_tolerance_is_clean_match() {
        // 基准额 1000 vs 1005, |Δ|=5 ≤ 容差 10 → 汇率对上, 干净匹配。
        assertThat(classifier.classify(crossCurrency(1000L, 1005L), ctx(10))).isNull();
    }

    @Test
    void base_outside_tolerance_is_fx_rate_diff() {
        Discrepancy d = classifier.classify(crossCurrency(1000L, 1005L), ctx(3));
        assertThat(d).isNotNull();
        assertThat(d.type()).isEqualTo(DiscrepancyType.FX_RATE_DIFF);
        assertThat(d.expectedAmountMinor()).isEqualTo(1000L);
        assertThat(d.actualAmountMinor()).isEqualTo(1005L);
        assertThat(d.deltaAmountMinor()).isEqualTo(-5L);
        assertThat(d.currency()).isNull(); // 跨币, 基准币列未命名
    }

    @Test
    void missing_base_amounts_falls_back_to_currency_mismatch() {
        assertThat(classifier.classify(crossCurrency(null, 1005L), ctx(10)).type())
                .isEqualTo(DiscrepancyType.CURRENCY_MISMATCH);
        assertThat(classifier.classify(crossCurrency(1000L, null), ctx(10)).type())
                .isEqualTo(DiscrepancyType.CURRENCY_MISMATCH);
    }

    @Test
    void strict_zero_tolerance_flags_any_base_difference() {
        assertThat(classifier.classify(crossCurrency(1000L, 1001L), ctx(0)).type())
                .isEqualTo(DiscrepancyType.FX_RATE_DIFF);
        // 基准额完全相等 → 干净匹配。
        assertThat(classifier.classify(crossCurrency(1000L, 1000L), ctx(0))).isNull();
    }
}
