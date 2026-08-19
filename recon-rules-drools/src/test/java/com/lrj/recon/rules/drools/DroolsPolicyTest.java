package com.lrj.recon.rules.drools;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.service.ExactEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可配置化能力: 追加自定义 DRL 即改变判差行为, <b>无需改 Java</b> (B2 卖点)。
 */
class DroolsPolicyTest {

    private final EvaluationContext ctx = DroolsTestFixtures.plainContext();

    /** 自定义规则: 抑制 DUPLICATE。默认判差器仍会产 DUPLICATE, Drools 追加规则后不产。 */
    @Test
    void custom_rule_suppresses_duplicate_without_code_change() {
        String drl = """
                package com.lrj.recon.rules;
                import com.lrj.recon.rules.drools.DiscrepancyDecision;
                import com.lrj.recon.core.domain.model.DiscrepancyType;
                rule "ops: suppress duplicate"
                    when $d : DiscrepancyDecision(candidateType == DiscrepancyType.DUPLICATE, suppressed == false)
                    then $d.suppress();
                end
                """;
        DroolsDiscrepancyEvaluator drools = DroolsDiscrepancyEvaluator.withDefaultAnd(drl);
        DiscrepancyRule rule = DiscrepancyRule.exact();

        MatchGroup dup = onlyGroup("K-dup", dupGroups());
        assertThat(new ExactEvaluator().evaluate(dup, rule, ctx)).hasSize(1); // 内置仍判 DUPLICATE
        assertThat(drools.evaluate(dup, rule, ctx)).isEmpty();               // 规则抑制
    }

    /** 自定义规则: 把 AMOUNT_MISMATCH 改判为 TIMING; fingerprint 应按新 type 重算 (随 type 变)。 */
    @Test
    void custom_rule_overrides_type_and_recomputes_fingerprint() {
        String drl = """
                package com.lrj.recon.rules;
                import com.lrj.recon.rules.drools.DiscrepancyDecision;
                import com.lrj.recon.core.domain.model.DiscrepancyType;
                rule "ops: amount mismatch -> timing"
                    when $d : DiscrepancyDecision(candidateType == DiscrepancyType.AMOUNT_MISMATCH)
                    then $d.overrideType(DiscrepancyType.TIMING);
                end
                """;
        DroolsDiscrepancyEvaluator drools = DroolsDiscrepancyEvaluator.withDefaultAnd(drl);
        DiscrepancyRule rule = DiscrepancyRule.exact();

        MatchGroup amt = onlyGroup("K-amt", amtGroups());
        Discrepancy builtin = new ExactEvaluator().evaluate(amt, rule, ctx).get(0);
        List<Discrepancy> out = drools.evaluate(amt, rule, ctx);

        assertThat(out).hasSize(1);
        Discrepancy d = out.get(0);
        assertThat(d.type()).isEqualTo(DiscrepancyType.TIMING);
        assertThat(d.fingerprint()).isNotEqualTo(builtin.fingerprint()); // type 进 fingerprint → 变
        // 金额构造不变 (仍来自候选)。
        assertThat(d.expectedAmountMinor()).isEqualTo(builtin.expectedAmountMinor());
        assertThat(d.actualAmountMinor()).isEqualTo(builtin.actualAmountMinor());
    }

    private static List<MatchGroup> dupGroups() {
        return DroolsTestFixtures.join(
                List.of(DroolsTestFixtures.left("K-dup", 300)),
                List.of(DroolsTestFixtures.right("K-dup", 300), DroolsTestFixtures.right("K-dup", 300)));
    }

    private static List<MatchGroup> amtGroups() {
        return DroolsTestFixtures.join(
                List.of(DroolsTestFixtures.left("K-amt", 1000)),
                List.of(DroolsTestFixtures.right("K-amt", 900)));
    }

    private static MatchGroup onlyGroup(String key, List<MatchGroup> groups) {
        return groups.stream()
                .filter(g -> g.matchKey() != null && key.equals(g.matchKey().value()))
                .findFirst().orElseThrow(() -> new AssertionError("no group " + key));
    }
}
