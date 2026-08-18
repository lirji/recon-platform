package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.spi.DiscrepancyEvaluator;

import java.util.List;

/**
 * 容差判差器 (M4, 设计 §4/§11): 委托 {@link DiscrepancyClassifier} 得到一组主类型, 但对
 * {@link DiscrepancyType#AMOUNT_MISMATCH} 应用<b>双阈值金额容差</b> —— 绝对阈值 {@code absToleranceMinor}
 * 与比例阈值 {@code ratioToleranceBps} (bps = 万分之一)。{@code |expected - actual|} 落在容差内则<b>视为匹配、不产
 * AMOUNT_MISMATCH</b> (返回空); 容差外仍产 AMOUNT_MISMATCH。
 *
 * <p><b>双阈值语义 (取更宽者)</b>: 满足<b>任一</b>阈值即视为容差内 ——
 * {@code |Δ| <= absToleranceMinor} 或 {@code |Δ| * 10000 <= ratioToleranceBps * |expected|}。
 * 比例阈值以<b>权威侧 expected</b> 为基数; 全程整数交叉相乘避 double (溢出 {@link MoneyMath#multiplyExact} fail-fast)。
 * 阈值 &le; 0 表示该阈值关闭 (不放宽)。
 *
 * <p><b>纯函数、零框架、无副作用</b>; 阈值取自传入的 {@link DiscrepancyRule} (与 {@link ExactEvaluator} 一样在
 * evaluate 期读 rule, 故工厂无需构造期阈值)。
 *
 * <p><b>范围</b>: 容差只作用于 1:1 的 AMOUNT_MISMATCH。GROUP_SUM_MISMATCH / TIMING / STATUS 等其它类型不放宽
 * (与设计"容差=金额容差"一致); 被容差抹平的组按<b>干净匹配</b>路由 (左额→matchedLeft, 右额→matchedRight),
 * 守恒仍构造性闭合 (左右额分别独立入账, 见 {@link ConservationAccumulator})。
 */
public final class ToleranceEvaluator implements DiscrepancyEvaluator {

    public static final String EVALUATOR_ID = "tolerance";

    private static final long BPS_DENOMINATOR = 10_000L;

    private final DiscrepancyClassifier classifier;

    public ToleranceEvaluator() {
        this(new DiscrepancyClassifier());
    }

    public ToleranceEvaluator(DiscrepancyClassifier classifier) {
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
            return List.of();
        }
        if (d.type() == DiscrepancyType.AMOUNT_MISMATCH && rule != null && withinTolerance(d, rule)) {
            return List.of(); // 容差内: 视为匹配, 不产差
        }
        return List.of(d);
    }

    /** |expected-actual| 是否落在绝对或比例阈值内 (取更宽者)。 */
    private boolean withinTolerance(Discrepancy d, DiscrepancyRule rule) {
        // 修复 C: 用溢出安全的 Math.absExact 而非裸 Math.abs —— Math.abs(Long.MIN_VALUE) 仍为负 (回绕),
        // 会破坏本模块 fail-fast-on-overflow 语义, 且经绝对阈值分支 (负 delta <= 任意正阈值恒真) 静默吞掉真
        // AMOUNT_MISMATCH。absExact 溢出抛 ArithmeticException, 与 addExact/subtractExact/multiplyExact 一致。
        long delta = Math.absExact(MoneyMath.subtractExact(d.expectedAmountMinor(), d.actualAmountMinor()));

        long absThreshold = rule.absToleranceMinor();
        if (absThreshold > 0 && delta <= absThreshold) {
            return true;
        }

        long ratioBps = rule.ratioToleranceBps();
        if (ratioBps > 0) {
            long base = Math.absExact(d.expectedAmountMinor()); // 同理: |expected| 作比例基数, MIN_VALUE 溢出 fail-fast
            // |Δ|/|expected| <= ratioBps/10000  ⇔  |Δ|*10000 <= ratioBps*|expected| (整数, 避 double)
            long lhs = MoneyMath.multiplyExact(delta, BPS_DENOMINATOR);
            long rhs = MoneyMath.multiplyExact(ratioBps, base);
            return lhs <= rhs;
        }
        return false;
    }
}
