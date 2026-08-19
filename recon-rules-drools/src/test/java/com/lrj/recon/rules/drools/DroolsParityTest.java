package com.lrj.recon.rules.drools;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.service.ExactEvaluator;
import com.lrj.recon.core.domain.service.ToleranceEvaluator;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核心 parity: 默认规则集下 {@link DroolsDiscrepancyEvaluator} 与 {@link ExactEvaluator}/{@link ToleranceEvaluator}
 * 对同一批组产出<b>逐字段一致</b>的 Discrepancy (type/fingerprint/金额)。锁死"默认 Drools ≡ 内置判差器"。
 */
class DroolsParityTest {

    private final DroolsDiscrepancyEvaluator drools = DroolsDiscrepancyEvaluator.withDefaultRules();

    @Test
    void matches_exact_evaluator_across_all_types() {
        DiscrepancyEvaluator exact = new ExactEvaluator();
        DiscrepancyRule rule = DiscrepancyRule.exact();

        assertParity(exact, drools, rule, DroolsTestFixtures.plainContext(), DroolsTestFixtures.mixedPlainGroups());
        assertParity(exact, drools, rule, DroolsTestFixtures.spineContext(), DroolsTestFixtures.bridgeBrokenGroups());
    }

    @Test
    void matches_tolerance_evaluator_when_tolerance_configured() {
        DiscrepancyEvaluator tolerance = new ToleranceEvaluator();
        // |Δ|=100 (K-amt: 1000 vs 900) 落绝对阈值 200 内 → 两者都应抹平该 AMOUNT_MISMATCH。
        DiscrepancyRule rule = DiscrepancyRule.builder()
                .evaluatorType(EvaluatorType.TOLERANCE).absToleranceMinor(200).build();

        assertParity(tolerance, drools, rule, DroolsTestFixtures.plainContext(), DroolsTestFixtures.mixedPlainGroups());
    }

    @Test
    void disabled_type_is_suppressed_like_builtin() {
        DiscrepancyEvaluator exact = new ExactEvaluator();
        // 关闭 AMOUNT_MISMATCH: 两者都应抹平 K-amt。
        DiscrepancyRule rule = DiscrepancyRule.builder()
                .enabled(java.util.EnumSet.complementOf(java.util.EnumSet.of(DiscrepancyType.AMOUNT_MISMATCH)))
                .build();
        assertParity(exact, drools, rule, DroolsTestFixtures.plainContext(), DroolsTestFixtures.mixedPlainGroups());
    }

    private static void assertParity(DiscrepancyEvaluator builtin, DiscrepancyEvaluator drools,
                                     DiscrepancyRule rule, EvaluationContext ctx, List<MatchGroup> groups) {
        assertThat(groups).isNotEmpty();
        for (MatchGroup g : groups) {
            List<Discrepancy> a = builtin.evaluate(g, rule, ctx);
            List<Discrepancy> b = drools.evaluate(g, rule, ctx);
            assertThat(b).as("size for group %s", g).hasSameSizeAs(a);
            for (int i = 0; i < a.size(); i++) {
                Discrepancy x = a.get(i);
                Discrepancy y = b.get(i);
                assertThat(y.type()).as("type").isEqualTo(x.type());
                assertThat(y.fingerprint()).as("fingerprint").isEqualTo(x.fingerprint());
                assertThat(y.expectedAmountMinor()).as("expected").isEqualTo(x.expectedAmountMinor());
                assertThat(y.actualAmountMinor()).as("actual").isEqualTo(x.actualAmountMinor());
                assertThat(y.deltaAmountMinor()).as("delta").isEqualTo(x.deltaAmountMinor());
                assertThat(y.currency()).as("currency").isEqualTo(x.currency());
                assertThat(y.bridgeBreakStage()).as("bridgeStage").isEqualTo(x.bridgeBreakStage());
            }
        }
    }
}
