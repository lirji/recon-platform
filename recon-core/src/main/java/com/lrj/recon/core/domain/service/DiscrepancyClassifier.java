package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.Presence;
import com.lrj.recon.core.domain.model.SourceRole;

import java.time.Instant;
import java.util.Objects;

/**
 * 差异分类器: 对一个 {@link MatchGroup} 按设计 §9 优先级判定, <b>一组只发一条主类型</b>; 无差异返回 {@code null} (干净匹配)。
 *
 * <p>优先级 (高→低):
 * BRIDGE_BROKEN &gt; CURRENCY_MISMATCH &gt; DUPLICATE/EXTRA &gt; GROUP_SUM_MISMATCH
 * &gt; AMOUNT_MISMATCH &gt; STATUS_MISMATCH &gt; TIMING &gt; MISSING。
 * <ul>
 *   <li>缺失侧 role == spineRole → BRIDGE_BROKEN 并按 stage 归因, 压制 MISSING (不叠加);</li>
 *   <li>两侧币种不一致 → CURRENCY_MISMATCH 短路, 不进任何数值比较;</li>
 *   <li>TIMING: 两侧金额/状态一致但 posting_time 跨日 (窗口内) → TIMING, 不判 MISSING;</li>
 *   <li>STATUS_MISMATCH / TIMING 仅对 <b>1:1</b> 组判定; 多行组以 GROUP_SUM_MISMATCH 收口, 不下探这两类。</li>
 * </ul>
 */
public final class DiscrepancyClassifier {

    private static final long SECONDS_PER_DAY = 86_400L;

    /**
     * @return 判定的主差异 (含 fingerprint / expected-actual-delta / currency / bridgeStage);
     * {@code null} 表示该组干净匹配。
     */
    public Discrepancy classify(MatchGroup group, EvaluationContext ctx) {
        Presence presence = group.presence();

        // 以下 if/else 分支顺序即优先级, 必须与 DiscrepancyType.precedence() 保持一致 (同一优先级的两处表达)。
        // ---- presence 分支: BRIDGE_BROKEN / MISSING / EXTRA ----
        if (presence == Presence.LEFT_ONLY) {         // 右侧缺失
            if (isSpine(ctx.rightRole(), ctx.spineRole())) {
                return bridgeBrokenLeftPresent(group, ctx);
            }
            return leftOnlyDiscrepancy(group, ctx, DiscrepancyType.MISSING);
        }
        if (presence == Presence.RIGHT_ONLY) {        // 左侧缺失
            if (isSpine(ctx.leftRole(), ctx.spineRole())) {
                return bridgeBrokenRightPresent(group, ctx);
            }
            return rightOnlyDiscrepancy(group, ctx, DiscrepancyType.EXTRA);
        }

        // ---- BOTH 分支 ----
        if (!group.isCurrencyConsistent()) {
            return currencyMismatch(group, ctx);
        }
        if (group.duplicate()) {
            return bothPresent(group, ctx, DiscrepancyType.DUPLICATE);
        }

        long left = group.sumSignedLeftMinor();
        long right = group.sumSignedRightMinor();

        if (group.isMultiLine()) {
            if (left != right) {
                return bothPresent(group, ctx, DiscrepancyType.GROUP_SUM_MISMATCH);
            }
            // 合法多行组, signed 和相等 → 匹配 (含 0/负和场景, 无假阳性)。
            // STATUS/TIMING 仅对 1:1 组判定 (多行组的代表状态/时点无定义), 故此处刻意不下探。
            return null;
        }

        // 1:1
        if (left != right) {
            return bothPresent(group, ctx, DiscrepancyType.AMOUNT_MISMATCH);
        }
        if (statusMismatch(group)) {
            return bothPresent(group, ctx, DiscrepancyType.STATUS_MISMATCH);
        }
        if (crossDayTiming(group, ctx)) {
            return bothPresent(group, ctx, DiscrepancyType.TIMING);
        }
        return null; // 干净匹配
    }

    private static boolean isSpine(SourceRole missingSideRole, SourceRole spineRole) {
        return spineRole != null && missingSideRole == spineRole;
    }

    private boolean statusMismatch(MatchGroup g) {
        String l = g.leftBizStatus();
        String r = g.rightBizStatus();
        return l != null && r != null && !l.equals(r);
    }

    private boolean crossDayTiming(MatchGroup g, EvaluationContext ctx) {
        Instant lt = g.leftPostingTime();
        Instant rt = g.rightPostingTime();
        if (lt == null || rt == null) {
            return false;
        }
        long leftDay = Math.floorDiv(lt.getEpochSecond(), SECONDS_PER_DAY);
        long rightDay = Math.floorDiv(rt.getEpochSecond(), SECONDS_PER_DAY);
        if (leftDay == rightDay) {
            return false;
        }
        if (ctx.hasWindow()) {
            return withinWindow(lt, ctx) && withinWindow(rt, ctx);
        }
        return true;
    }

    private boolean withinWindow(Instant t, EvaluationContext ctx) {
        return !t.isBefore(ctx.matchWindowFrom()) && !t.isAfter(ctx.matchWindowTo());
    }

    // ---------- Discrepancy 构造 ----------

    private Discrepancy leftOnlyDiscrepancy(MatchGroup g, EvaluationContext ctx, DiscrepancyType type) {
        long left = g.sumSignedLeftMinor();
        return base(g, ctx, type, null)
                .currency(g.leftCurrency())
                .expectedAmountMinor(left)
                .actualAmountMinor(0L)
                .deltaAmountMinor(left)
                .leftRawRef(g.leftSampleRawRef())
                .build();
    }

    private Discrepancy rightOnlyDiscrepancy(MatchGroup g, EvaluationContext ctx, DiscrepancyType type) {
        long right = g.sumSignedRightMinor();
        return base(g, ctx, type, null)
                .currency(g.rightCurrency())
                .expectedAmountMinor(0L)
                .actualAmountMinor(right)
                .deltaAmountMinor(MoneyMath.subtractExact(0L, right))
                .rightRawRef(g.rightSampleRawRef())
                .build();
    }

    private Discrepancy bridgeBrokenLeftPresent(MatchGroup g, EvaluationContext ctx) {
        long left = g.sumSignedLeftMinor();
        return base(g, ctx, DiscrepancyType.BRIDGE_BROKEN, ctx.stageLabel())
                .currency(g.leftCurrency())
                .expectedAmountMinor(left)
                .actualAmountMinor(0L)
                .deltaAmountMinor(left)
                .leftRawRef(g.leftSampleRawRef())
                .build();
    }

    private Discrepancy bridgeBrokenRightPresent(MatchGroup g, EvaluationContext ctx) {
        long right = g.sumSignedRightMinor();
        return base(g, ctx, DiscrepancyType.BRIDGE_BROKEN, ctx.stageLabel())
                .currency(g.rightCurrency())
                .expectedAmountMinor(0L)
                .actualAmountMinor(right)
                .deltaAmountMinor(MoneyMath.subtractExact(0L, right))
                .rightRawRef(g.rightSampleRawRef())
                .build();
    }

    private Discrepancy currencyMismatch(MatchGroup g, EvaluationContext ctx) {
        // 横跨两币种: currency=null, 不进数值比较; 左右额分落各自币种桶 (由 ConservationChecker 处理)。
        return base(g, ctx, DiscrepancyType.CURRENCY_MISMATCH, null)
                .currency(null)
                .expectedAmountMinor(g.sumSignedLeftMinor())
                .actualAmountMinor(g.sumSignedRightMinor())
                .deltaAmountMinor(0L)
                .leftRawRef(g.leftSampleRawRef())
                .rightRawRef(g.rightSampleRawRef())
                .build();
    }

    private Discrepancy bothPresent(MatchGroup g, EvaluationContext ctx, DiscrepancyType type) {
        long left = g.sumSignedLeftMinor();
        long right = g.sumSignedRightMinor();
        return base(g, ctx, type, null)
                .currency(g.leftCurrency())
                .expectedAmountMinor(left)
                .actualAmountMinor(right)
                .deltaAmountMinor(MoneyMath.subtractExact(left, right))
                .leftRawRef(g.leftSampleRawRef())
                .rightRawRef(g.rightSampleRawRef())
                .build();
    }

    private Discrepancy.Builder base(MatchGroup g, EvaluationContext ctx,
                                     DiscrepancyType type, String bridgeStage) {
        String groupKeyVal = g.groupKey() == null ? null : g.groupKey().value();
        String matchKeyVal = g.matchKey() == null ? null : g.matchKey().value();
        String fingerprint = Fingerprint.of(
                ctx.scenarioCode(), ctx.accountingPeriod(), ctx.segmentId(),
                type.name(), groupKeyVal, matchKeyVal, bridgeStage);
        return Discrepancy.builder()
                .discrepancyId(fingerprint) // M0 领域内以 fingerprint 为身份; 持久化层 (M1) 再分配主键
                .runId(ctx.runId())
                .segmentId(Objects.requireNonNullElse(ctx.segmentId(), g.matchKey() == null ? null : g.matchKey().fieldName()))
                .type(type)
                .bridgeBreakStage(bridgeStage)
                .groupKey(groupKeyVal)
                .matchKey(matchKeyVal)
                .fingerprint(fingerprint);
    }
}
