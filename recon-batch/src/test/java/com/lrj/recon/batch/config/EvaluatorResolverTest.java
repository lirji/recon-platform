package com.lrj.recon.batch.config;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.service.ExactEvaluator;
import com.lrj.recon.core.domain.service.ToleranceEvaluator;
import com.lrj.recon.core.spi.DroolsEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组合根判差器装配口 (B2): EXACT/TOLERANCE 走 core;DROOLS 路由到注入的 bean; 未启用 Drools 配 DROOLS → fail-fast。
 */
class EvaluatorResolverTest {

    private static final DroolsEvaluator STUB = new DroolsEvaluator() {
        @Override public String evaluatorId() { return "stub-drools"; }
        @Override public List<Discrepancy> evaluate(MatchGroup g, DiscrepancyRule r, EvaluationContext c) { return List.of(); }
    };

    @Test
    void routes_exact_and_tolerance_to_core_factory() {
        EvaluatorResolver resolver = new EvaluatorResolver(null);
        assertThat(resolver.resolve(EvaluatorType.EXACT)).isInstanceOf(ExactEvaluator.class);
        assertThat(resolver.resolve(EvaluatorType.TOLERANCE)).isInstanceOf(ToleranceEvaluator.class);
    }

    @Test
    void routes_drools_to_injected_bean() {
        EvaluatorResolver resolver = new EvaluatorResolver(STUB);
        assertThat(resolver.resolve(EvaluatorType.DROOLS)).isSameAs(STUB);
    }

    @Test
    void drools_without_engine_fails_fast_never_falls_back() {
        EvaluatorResolver resolver = new EvaluatorResolver(null);
        assertThatThrownBy(() -> resolver.resolve(EvaluatorType.DROOLS))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("recon.rules.drools.enabled=true");
    }
}
