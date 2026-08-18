package com.lrj.recon.core.domain.service;

import com.lrj.recon.core.domain.model.ConservationPartial;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.Presence;
import com.lrj.recon.core.domain.model.ReconReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造性双向守恒的<b>单一累计引擎</b> (设计 §8)。M3 把守恒从"二次全量重放"改成"匹配判差单遍流式累计",
 * 本类是三条路径共用的<b>唯一</b>路由/求和真源, 从而保证三者<b>逐字段等价</b>:
 * <ol>
 *   <li><b>单遍</b> (M3 Step2 每 partition): {@link #accept(ClassifiedGroup)} 逐组累计 → {@link #toPartials} 落局部结果;</li>
 *   <li><b>汇总</b> (M3 汇总步): {@link #acceptPartial(ConservationPartial)} 跨 bucket 合并同 (segment,currency) 子项
 *       → {@link #toReports};</li>
 *   <li><b>双遍</b> (M2 {@link ConservationChecker}): 整段一次性 {@link #accept} → {@link #toReports} (等价基线)。</li>
 * </ol>
 *
 * <p>每组两侧带符号额被<b>恰好一次</b>路由到 (currency) 桶内左/右口径某一子项, 故 residual 由构造恒为 0
 * (见 {@link ConservationChecker} 说明: residual≡0 只抓桶路由被改坏/溢出, 不证明分类判定正确)。
 * 全程 {@link MoneyMath#addExact} 溢出 fail-fast; 按 currency 分桶, 跨币不相加。
 *
 * <p><b>无框架、可变、非线程安全</b>: 每个 partition / 每次汇总各用<b>独立实例</b> (并行下无共享可变状态)。
 */
public final class ConservationAccumulator {

    private final Map<String, Bucket> buckets = new LinkedHashMap<>();

    /** 累计一个分类组 (type==null 为干净匹配)。路由口径严格沿用设计 §8 双 matched 构造。 */
    public void accept(ClassifiedGroup cg) {
        MatchGroup g = cg.group();
        DiscrepancyType type = cg.type();
        long left = g.sumSignedLeftMinor();
        long right = g.sumSignedRightMinor();

        // CURRENCY_MISMATCH: 左右额分落各自币种桶, 都不计入 matched。
        if (type == DiscrepancyType.CURRENCY_MISMATCH) {
            Bucket lb = bucket(g.leftCurrency());
            lb.expectedTotal = MoneyMath.addExact(lb.expectedTotal, left);
            lb.currencyMismatchLeft = MoneyMath.addExact(lb.currencyMismatchLeft, left);
            Bucket rb = bucket(g.rightCurrency());
            rb.rightSideTotal = MoneyMath.addExact(rb.rightSideTotal, right);
            rb.currencyMismatchRight = MoneyMath.addExact(rb.currencyMismatchRight, right);
            return;
        }

        Presence presence = g.presence();
        switch (presence) {
            case BOTH -> {
                if (!g.isCurrencyConsistent()) { // 非 CURRENCY_MISMATCH 的 BOTH 组必须同币, 否则跨币入桶
                    throw new IllegalStateException(
                            "BOTH-present non-CURRENCY_MISMATCH group must be currency-consistent: " + g.matchKey());
                }
                Bucket b = bucket(g.leftCurrency()); // 币种一致, leftCcy == rightCcy
                b.expectedTotal = MoneyMath.addExact(b.expectedTotal, left);
                b.rightSideTotal = MoneyMath.addExact(b.rightSideTotal, right);
                if (type == null) {
                    b.matchedLeft = MoneyMath.addExact(b.matchedLeft, left);
                    b.matchedRight = MoneyMath.addExact(b.matchedRight, right);
                } else {
                    b.matchedRight = MoneyMath.addExact(b.matchedRight, right);
                    switch (type) {
                        case AMOUNT_MISMATCH -> b.amountMismatchLeft = MoneyMath.addExact(b.amountMismatchLeft, left);
                        case STATUS_MISMATCH -> b.statusLeft = MoneyMath.addExact(b.statusLeft, left);
                        case TIMING -> b.timingLeft = MoneyMath.addExact(b.timingLeft, left);
                        case GROUP_SUM_MISMATCH -> b.groupSumLeft = MoneyMath.addExact(b.groupSumLeft, left);
                        case DUPLICATE -> b.duplicateLeft = MoneyMath.addExact(b.duplicateLeft, left);
                        default -> throw new IllegalStateException("unexpected BOTH-present type: " + type);
                    }
                }
            }
            case LEFT_ONLY -> {
                Bucket b = bucket(g.leftCurrency());
                b.expectedTotal = MoneyMath.addExact(b.expectedTotal, left);
                if (type == DiscrepancyType.MISSING) {
                    b.missing = MoneyMath.addExact(b.missing, left);
                } else if (type == DiscrepancyType.BRIDGE_BROKEN) {
                    b.bridgeBrokenLeft = MoneyMath.addExact(b.bridgeBrokenLeft, left);
                } else {
                    throw new IllegalStateException("unexpected LEFT_ONLY type: " + type);
                }
            }
            case RIGHT_ONLY -> {
                Bucket b = bucket(g.rightCurrency());
                b.rightSideTotal = MoneyMath.addExact(b.rightSideTotal, right);
                if (type == DiscrepancyType.EXTRA) {
                    b.extra = MoneyMath.addExact(b.extra, right);
                } else if (type == DiscrepancyType.BRIDGE_BROKEN) {
                    b.bridgeBrokenRight = MoneyMath.addExact(b.bridgeBrokenRight, right);
                } else {
                    throw new IllegalStateException("unexpected RIGHT_ONLY type: " + type);
                }
            }
            default -> throw new IllegalStateException("unknown presence: " + presence);
        }
    }

    /** 合并一份局部结果 (汇总步): 把同 currency 的各子项逐个 addExact 累计。 */
    public void acceptPartial(ConservationPartial p) {
        Bucket b = bucket(p.currency());
        b.expectedTotal = MoneyMath.addExact(b.expectedTotal, p.expectedTotalMinor());
        b.rightSideTotal = MoneyMath.addExact(b.rightSideTotal, p.rightSideTotalMinor());
        b.matchedLeft = MoneyMath.addExact(b.matchedLeft, p.matchedLeftMinor());
        b.matchedRight = MoneyMath.addExact(b.matchedRight, p.matchedRightMinor());
        b.missing = MoneyMath.addExact(b.missing, p.missingMinor());
        b.extra = MoneyMath.addExact(b.extra, p.extraMinor());
        b.amountMismatchLeft = MoneyMath.addExact(b.amountMismatchLeft, p.amountMismatchLeftMinor());
        b.statusLeft = MoneyMath.addExact(b.statusLeft, p.statusLeftMinor());
        b.timingLeft = MoneyMath.addExact(b.timingLeft, p.timingLeftMinor());
        b.groupSumLeft = MoneyMath.addExact(b.groupSumLeft, p.groupSumLeftMinor());
        b.duplicateLeft = MoneyMath.addExact(b.duplicateLeft, p.duplicateLeftMinor());
        b.bridgeBrokenLeft = MoneyMath.addExact(b.bridgeBrokenLeft, p.bridgeBrokenLeftMinor());
        b.bridgeBrokenRight = MoneyMath.addExact(b.bridgeBrokenRight, p.bridgeBrokenRightMinor());
        b.currencyMismatchLeft = MoneyMath.addExact(b.currencyMismatchLeft, p.currencyMismatchLeftMinor());
        b.currencyMismatchRight = MoneyMath.addExact(b.currencyMismatchRight, p.currencyMismatchRightMinor());
    }

    /** 每个 (currency) 桶产一份最终 {@link ReconReport} (含 residual/balanced)。 */
    public List<ReconReport> toReports(String runId, String segmentId) {
        List<ReconReport> reports = new ArrayList<>(buckets.size());
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
            reports.add(e.getValue().toReport(runId, segmentId, e.getKey()));
        }
        return reports;
    }

    /**
     * 每个 (currency) 桶产一份 {@link ConservationPartial} (原始子项, 供跨 bucket 汇总)。
     * {@code subIndex} 用于二级 sub-bucket 拆分时唯一化局部结果 (未拆传 -1)。
     */
    public List<ConservationPartial> toPartials(String runId, String segmentId, int bucket, int subIndex) {
        List<ConservationPartial> partials = new ArrayList<>(buckets.size());
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
            partials.add(e.getValue().toPartial(runId, segmentId, bucket, subIndex, e.getKey()));
        }
        return partials;
    }

    /** 当前是否累计到任何 (currency) 桶 (空 bucket 无组时 partition 不落局部结果)。 */
    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    private Bucket bucket(String currency) {
        if (currency == null) {
            throw new IllegalArgumentException("currency must not be null for a present side");
        }
        return buckets.computeIfAbsent(currency, c -> new Bucket());
    }

    /** 单币种桶累加器 (内部可变); 左右口径子项分开记, 便于既产报表列/局部结果又算 residual。 */
    private static final class Bucket {
        long expectedTotal;
        long rightSideTotal;
        long matchedLeft;
        long matchedRight;
        long missing;
        long extra;
        long amountMismatchLeft;
        long statusLeft;
        long timingLeft;
        long groupSumLeft;
        long duplicateLeft;
        long bridgeBrokenLeft;
        long bridgeBrokenRight;
        long currencyMismatchLeft;
        long currencyMismatchRight;

        ReconReport toReport(String runId, String segmentId, String currency) {
            long leftAccounted = sum(matchedLeft, missing, amountMismatchLeft, groupSumLeft,
                    timingLeft, statusLeft, duplicateLeft, bridgeBrokenLeft, currencyMismatchLeft);
            long rightAccounted = sum(matchedRight, extra, bridgeBrokenRight, currencyMismatchRight);
            long leftResidual = MoneyMath.subtractExact(expectedTotal, leftAccounted);
            long rightResidual = MoneyMath.subtractExact(rightSideTotal, rightAccounted);

            return ReconReport.builder()
                    .runId(runId)
                    .segmentId(segmentId)
                    .currency(currency)
                    .expectedTotalMinor(expectedTotal)
                    .matchedAmountMinor(matchedLeft)
                    .amountMismatchMinor(amountMismatchLeft)
                    .missingMinor(missing)
                    .duplicateMinor(duplicateLeft)
                    .extraMinor(extra)
                    .timingMinor(timingLeft)
                    .statusMismatchMinor(statusLeft)
                    .currencyMismatchMinor(MoneyMath.addExact(currencyMismatchLeft, currencyMismatchRight))
                    // groupSumLeft = 左组 signed 净额 (非失配幅度); 失配幅度见 Discrepancy.deltaAmountMinor
                    .groupSumMismatchMinor(groupSumLeft)
                    .bridgeBrokenMinor(MoneyMath.addExact(bridgeBrokenLeft, bridgeBrokenRight))
                    .rightSideTotalMinor(rightSideTotal)
                    .leftResidualMinor(leftResidual)
                    .rightResidualMinor(rightResidual)
                    .balanced(leftResidual == 0L && rightResidual == 0L)
                    .build();
        }

        ConservationPartial toPartial(String runId, String segmentId, int bucket, int subIndex, String currency) {
            return ConservationPartial.builder()
                    .runId(runId).segmentId(segmentId).bucket(bucket).subIndex(subIndex).currency(currency)
                    .expectedTotalMinor(expectedTotal)
                    .rightSideTotalMinor(rightSideTotal)
                    .matchedLeftMinor(matchedLeft)
                    .matchedRightMinor(matchedRight)
                    .missingMinor(missing)
                    .extraMinor(extra)
                    .amountMismatchLeftMinor(amountMismatchLeft)
                    .statusLeftMinor(statusLeft)
                    .timingLeftMinor(timingLeft)
                    .groupSumLeftMinor(groupSumLeft)
                    .duplicateLeftMinor(duplicateLeft)
                    .bridgeBrokenLeftMinor(bridgeBrokenLeft)
                    .bridgeBrokenRightMinor(bridgeBrokenRight)
                    .currencyMismatchLeftMinor(currencyMismatchLeft)
                    .currencyMismatchRightMinor(currencyMismatchRight)
                    .build();
        }

        private static long sum(long... vs) {
            long acc = 0L;
            for (long v : vs) {
                acc = MoneyMath.addExact(acc, v);
            }
            return acc;
        }
    }
}
