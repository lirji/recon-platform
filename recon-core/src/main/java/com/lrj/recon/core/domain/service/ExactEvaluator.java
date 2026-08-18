package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;

import java.util.List;

/**
 * MVP 判差器: 精确比较 (零容差), 委托 {@link DiscrepancyClassifier} 得到一组的单条主类型。
 *
 * <p>纯函数: 相同输入永远相同输出, 无副作用。容差 (TOLERANCE) 归 M4。
 */
public final class ExactEvaluator implements DiscrepancyEvaluator {

    public static final String EVALUATOR_ID = "exact";

    private final DiscrepancyClassifier classifier;

    public ExactEvaluator() {
        this(new DiscrepancyClassifier());
    }

    public ExactEvaluator(DiscrepancyClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public String evaluatorId() {
        return EVALUATOR_ID;
    }

    @Override
    public List<Discrepancy> evaluate(MatchGroup group, DiscrepancyRule rule, EvaluationContext ctx) {
        Discrepancy d = classifier.classify(group, ctx);
        if (d == null) {
            return List.of();
        }
        if (rule != null && !rule.isEnabled(d.type())) {
            // 该类型被规则禁用 → 不产差 (MVP 默认全开)。
            return List.of();
        }
        return List.of(d);
    }
}
