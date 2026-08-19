package com.lrj.recon.rules.drools;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyRule;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.service.DiscrepancyClassifier;
import com.lrj.recon.core.domain.service.Fingerprint;
import com.lrj.recon.core.spi.DroolsEvaluator;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 阶段二 Drools 判差器 (B2)。
 *
 * <p><b>策略层设计</b>: 先用 recon-core 的 {@link DiscrepancyClassifier} 得<b>候选</b>差异 (保留 fingerprint /
 * bridge 归因 / null-key 鉴别量 / 金额构造等安全关键逻辑), 再把候选投影成 {@link DiscrepancyDecision} 交 DRL 规则
 * 做<b>策略后处理</b> (suppress / 改判)。DRL 不重写分类器 —— 避免 parity 灾难与破坏守恒/人工处置 re-link。
 *
 * <p><b>默认规则集</b> ({@code rules/discrepancy-default.drl}) 只做: ①候选 type 被 {@code rule} 关掉 → suppress;
 * ②AMOUNT_MISMATCH 且落容差内 → suppress。故默认装配下本判差器 ≡ {@code ExactEvaluator}/{@code ToleranceEvaluator}
 * (由 parity 测试锁定); ops 追加 DRL 才改变行为。
 *
 * <p><b>fail-fast (红线)</b>: DRL 编译失败 → 构造期抛异常 (启动即失败, 绝不带病判差); 运行期规则异常向上抛
 * (让批作业 FAILED)。不提供"Drools 挂了静默回退 Exact"的兜底。
 */
public final class DroolsDiscrepancyEvaluator implements DroolsEvaluator {

    public static final String EVALUATOR_ID = "drools";
    private static final String DEFAULT_RULES_CLASSPATH = "rules/discrepancy-default.drl";
    private static final long BPS_DENOMINATOR = 10_000L;

    private final DiscrepancyClassifier classifier;
    private final KieContainer kieContainer;

    /** 用默认规则集装配 (等价 Exact/Tolerance)。 */
    public static DroolsDiscrepancyEvaluator withDefaultRules() {
        return new DroolsDiscrepancyEvaluator(new DiscrepancyClassifier(), List.of(loadClasspath(DEFAULT_RULES_CLASSPATH)));
    }

    /** 默认规则集 + 追加自定义 DRL (ops 策略)。 */
    public static DroolsDiscrepancyEvaluator withDefaultAnd(String... extraDrl) {
        java.util.List<String> drls = new java.util.ArrayList<>();
        drls.add(loadClasspath(DEFAULT_RULES_CLASSPATH));
        drls.addAll(List.of(extraDrl));
        return new DroolsDiscrepancyEvaluator(new DiscrepancyClassifier(), drls);
    }

    /**
     * @param classifier 领域分类器 (产候选)
     * @param drlContents 一或多份 DRL 源文本; 编译失败 fail-fast
     */
    public DroolsDiscrepancyEvaluator(DiscrepancyClassifier classifier, List<String> drlContents) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.kieContainer = compile(drlContents);
    }

    @Override
    public String evaluatorId() {
        return EVALUATOR_ID;
    }

    @Override
    public List<Discrepancy> evaluate(MatchGroup group, DiscrepancyRule rule, EvaluationContext ctx) {
        Discrepancy candidate = classifier.classify(group, ctx);
        if (candidate == null) {
            return List.of(); // 干净匹配: 与 Exact/Tolerance 早返回一致, 不触发规则
        }

        DiscrepancyDecision decision = project(candidate, group, rule, ctx);
        StatelessKieSession session = kieContainer.newStatelessKieSession();
        session.execute(decision); // insert + fireAllRules; 规则异常向上抛 (fail-fast)

        if (decision.isSuppressed()) {
            return List.of();
        }
        DiscrepancyType finalType = decision.resolvedType();
        if (finalType == candidate.type()) {
            return List.of(candidate);
        }
        return List.of(rebuildWithType(candidate, finalType, ctx));
    }

    // ---- 投影候选 → fact ----

    private DiscrepancyDecision project(Discrepancy c, MatchGroup group, DiscrepancyRule rule, EvaluationContext ctx) {
        boolean enabled = rule == null || rule.isEnabled(c.type());
        return DiscrepancyDecision.builder()
                .candidateType(c.type())
                .expectedAmountMinor(c.expectedAmountMinor())
                .actualAmountMinor(c.actualAmountMinor())
                .deltaAmountMinor(c.deltaAmountMinor())
                .absDeltaMinor(c.absDeltaMinor())
                .currency(c.currency())
                .multiLine(group.isMultiLine())
                .typeEnabled(enabled)
                .withinAmountTolerance(c.type() == DiscrepancyType.AMOUNT_MISMATCH && withinTolerance(c, rule))
                .absToleranceMinor(rule == null ? 0 : rule.absToleranceMinor())
                .ratioToleranceBps(rule == null ? 0 : rule.ratioToleranceBps())
                .scenarioCode(ctx.scenarioCode())
                .accountingPeriod(ctx.accountingPeriod())
                .segmentId(ctx.segmentId())
                .build();
    }

    /** 与 {@code ToleranceEvaluator} 同口径: |Δ|≤绝对阈值 或 |Δ|*10000≤bps*|expected| (取更宽者), 阈值≤0 关闭。 */
    private boolean withinTolerance(Discrepancy d, DiscrepancyRule rule) {
        if (rule == null) {
            return false;
        }
        long delta = Math.absExact(d.expectedAmountMinor() - d.actualAmountMinor());
        long absThreshold = rule.absToleranceMinor();
        if (absThreshold > 0 && delta <= absThreshold) {
            return true;
        }
        long ratioBps = rule.ratioToleranceBps();
        if (ratioBps > 0) {
            long base = Math.absExact(d.expectedAmountMinor());
            long lhs = Math.multiplyExact(delta, BPS_DENOMINATOR);
            long rhs = Math.multiplyExact(ratioBps, base);
            return lhs <= rhs;
        }
        return false;
    }

    /** 改判: 用新 type 重算 fingerprint (与 classifier 同口径); bridgeStage 仅 BRIDGE_BROKEN 保留。 */
    private Discrepancy rebuildWithType(Discrepancy c, DiscrepancyType finalType, EvaluationContext ctx) {
        String bridgeStage = finalType == DiscrepancyType.BRIDGE_BROKEN ? c.bridgeBreakStage() : null;
        String matchSlot = c.matchKey() != null ? c.matchKey()
                : (c.leftRawRef() != null ? c.leftRawRef() : c.rightRawRef());
        String fingerprint = Fingerprint.of(ctx.scenarioCode(), ctx.accountingPeriod(), ctx.segmentId(),
                finalType.name(), c.groupKey(), matchSlot, bridgeStage);
        return Discrepancy.builder()
                .discrepancyId(fingerprint)
                .runId(c.runId())
                .segmentId(c.segmentId())
                .type(finalType)
                .bridgeBreakStage(bridgeStage)
                .groupKey(c.groupKey())
                .matchKey(c.matchKey())
                .currency(c.currency())
                .expectedAmountMinor(c.expectedAmountMinor())
                .actualAmountMinor(c.actualAmountMinor())
                .deltaAmountMinor(c.deltaAmountMinor())
                .leftRawRef(c.leftRawRef())
                .rightRawRef(c.rightRawRef())
                .fingerprint(fingerprint)
                .build();
    }

    // ---- KIE 引导 (fail-fast) ----

    private static KieContainer compile(List<String> drlContents) {
        if (drlContents == null || drlContents.isEmpty()) {
            throw new IllegalArgumentException("at least one DRL source is required");
        }
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        int i = 0;
        for (String drl : drlContents) {
            if (drl == null || drl.isBlank()) {
                throw new IllegalArgumentException("DRL source must not be blank");
            }
            kfs.write("src/main/resources/rules/discrepancy-" + i++ + ".drl", drl);
        }
        KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
        Results results = kb.getResults();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException("Drools rule compilation failed (fail-fast, never skip evaluation): "
                    + results.getMessages(Message.Level.ERROR));
        }
        return ks.newKieContainer(ks.getRepository().getDefaultReleaseId());
    }

    private static String loadClasspath(String path) {
        try (InputStream in = DroolsDiscrepancyEvaluator.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("default DRL not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading DRL: " + path, e);
        }
    }
}
