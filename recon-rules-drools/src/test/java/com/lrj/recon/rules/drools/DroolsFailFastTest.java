package com.lrj.recon.rules.drools;

import com.lrj.recon.core.domain.service.DiscrepancyClassifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 红线: 规则加载失败必须 fail-fast (构造期抛), 绝不带病判差 / 静默跳过。
 */
class DroolsFailFastTest {

    @Test
    void bad_drl_fails_fast_at_construction() {
        String badDrl = """
                package com.lrj.recon.rules;
                rule "broken" when this is not valid drl then end
                """;
        assertThatThrownBy(() -> new DroolsDiscrepancyEvaluator(new DiscrepancyClassifier(), List.of(badDrl)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compilation failed");
    }

    @Test
    void empty_rules_fail_fast() {
        assertThatThrownBy(() -> new DroolsDiscrepancyEvaluator(new DiscrepancyClassifier(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_rule_fails_fast() {
        assertThatThrownBy(() -> new DroolsDiscrepancyEvaluator(new DiscrepancyClassifier(), List.of("   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
