package com.lrj.recon.batch.job;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;
import org.springframework.batch.item.ItemProcessor;

import java.util.List;

/**
 * matchEvaluateStep 的 processor (设计 §6/§9): 用 {@link DiscrepancyEvaluator} (M2/M3 = ExactEvaluator,
 * 内部委托 DiscrepancyClassifier) 对一个 {@link MatchGroup} 判差, 一组只发一条主类型。
 *
 * <p><b>M3 单遍守恒</b>: 对<b>每个</b>组都产出 {@link EvaluatedGroup} (绝不返回 null, 干净匹配也发, 只是
 * {@code discrepancy == null}) —— 让下游 {@link MatchEvaluateWriter} 能流式累计守恒 (需看到全部组), 消除 M2
 * report Step 的二次全量重放扫描。
 */
public class EvaluateProcessor implements ItemProcessor<MatchGroup, EvaluatedGroup> {

    private final DiscrepancyEvaluator evaluator;
    private final DiscrepancyRule rule;
    private final EvaluationContext ctx;

    public EvaluateProcessor(DiscrepancyEvaluator evaluator, DiscrepancyRule rule, EvaluationContext ctx) {
        this.evaluator = evaluator;
        this.rule = rule;
        this.ctx = ctx;
    }

    @Override
    public EvaluatedGroup process(MatchGroup group) {
        List<Discrepancy> found = evaluator.evaluate(group, rule, ctx);
        return new EvaluatedGroup(group, found.isEmpty() ? null : found.get(0));
    }
}
