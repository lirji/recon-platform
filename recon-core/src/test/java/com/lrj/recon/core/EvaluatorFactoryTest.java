package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.EvaluatorType;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.service.ClassifiedGroup;
import com.lrj.recon.core.domain.service.EvaluatorFactory;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluatorFactoryTest {

    @Test
    void creates_exact_evaluator() {
        DiscrepancyEvaluator e = EvaluatorFactory.create(EvaluatorType.EXACT);
        assertThat(e.evaluatorId()).isEqualTo("exact");
    }

    @Test
    void drools_fails_fast_never_silently_skips() {
        assertThatThrownBy(() -> EvaluatorFactory.create(EvaluatorType.DROOLS))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void tolerance_evaluator_wired_in_m4() {
        // M4: TOLERANCE 不再 fail-fast, 而是路由到 ToleranceEvaluator (阈值 evaluate 期从 rule 读)。
        DiscrepancyEvaluator e = EvaluatorFactory.create(EvaluatorType.TOLERANCE);
        assertThat(e.evaluatorId()).isEqualTo("tolerance");
    }

    @Test
    void exact_evaluator_emits_single_primary_type_for_amount_mismatch() {
        DiscrepancyEvaluator e = EvaluatorFactory.create(EvaluatorType.EXACT);
        EvaluationContext ctx = ReconFixtures.plainContext();
        ReconFixtures.Result r = ReconFixtures.run(ctx,
                List.of(ReconFixtures.left("K1", 100)),
                List.of(ReconFixtures.right("K1", 90)));
        MatchGroup group = r.groups().get(0);

        List<?> out = e.evaluate(group, DiscrepancyRule.exact(), ctx);
        assertThat(out).hasSize(1);

        // 校验与分类器结果一致 (evaluator 只是分类器的纯函数包装)
        assertThat(r.classified()).containsExactly(ClassifiedGroup.of(group, DiscrepancyType.AMOUNT_MISMATCH));
    }
}
