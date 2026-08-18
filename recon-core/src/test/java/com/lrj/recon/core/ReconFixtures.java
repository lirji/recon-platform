package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.ClassifiedGroup;
import com.lrj.recon.core.domain.service.DiscrepancyClassifier;
import com.lrj.recon.core.domain.service.SortMergeJoiner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 测试夹具: 造记录 + 跑 (sort-merge join → classify → 收集 ClassifiedGroup) 的单桶垂直切片。
 * 非测试类, 仅供其它 @Test 复用。
 */
final class ReconFixtures {

    static final String USD = "USD";
    static final String EUR = "EUR";
    static final Instant DAY1 = Instant.parse("2026-08-17T10:00:00Z");
    static final Instant DAY2 = Instant.parse("2026-08-18T09:00:00Z");
    static final Instant WINDOW_FROM = Instant.parse("2026-08-17T00:00:00Z");
    static final Instant WINDOW_TO = Instant.parse("2026-08-18T23:59:59Z");

    private ReconFixtures() {
    }

    static ReconRecord.Builder base(Side side, SourceRole role, String key, String ccy, long amountMinor, EntryType et) {
        return ReconRecord.builder()
                .recordId(side + ":" + role + ":" + key + ":" + amountMinor + ":" + et)
                .segmentId("SEG")
                .side(side)
                .sourceRole(role)
                .matchKey(MatchKey.of("key", key, 0))
                .groupKey(GroupKey.of("key", key)) // MVP: match_key == group_key
                .bucket(0)
                .money(Money.of(ccy, amountMinor))
                .entryType(et)
                .rawRef(side + ":" + key + ":" + amountMinor);
    }

    static ReconRecord left(String key, long amountMinor) {
        return base(Side.LEFT, SourceRole.MARKETING, key, USD, amountMinor, EntryType.ISSUE).build();
    }

    static ReconRecord right(String key, long amountMinor) {
        return base(Side.RIGHT, SourceRole.ACCOUNTING, key, USD, amountMinor, EntryType.ISSUE).build();
    }

    /** 通用单段上下文: 左营销 / 右账务, 无 spine (不触发 BRIDGE_BROKEN)。 */
    static EvaluationContext plainContext() {
        return EvaluationContext.builder()
                .runId("run-1").scenarioCode("scn").accountingPeriod("2026-08-17").segmentId("SEG")
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(null)
                .matchWindowFrom(WINDOW_FROM).matchWindowTo(WINDOW_TO)
                .build();
    }

    /** 单桶跑: 排序两侧 → sort-merge → 逐组 classify。 */
    static Result run(EvaluationContext ctx, List<ReconRecord> lefts, List<ReconRecord> rights) {
        List<ReconRecord> l = new ArrayList<>(lefts);
        List<ReconRecord> r = new ArrayList<>(rights);
        l.sort(Comparator.comparing(ReconRecord::matchKey));
        r.sort(Comparator.comparing(ReconRecord::matchKey));

        List<MatchGroup> groups = new ArrayList<>();
        new SortMergeJoiner().join(l.iterator(), r.iterator(), groups::add);

        DiscrepancyClassifier classifier = new DiscrepancyClassifier();
        List<Discrepancy> discrepancies = new ArrayList<>();
        List<ClassifiedGroup> classified = new ArrayList<>();
        for (MatchGroup g : groups) {
            Discrepancy d = classifier.classify(g, ctx);
            if (d != null) {
                discrepancies.add(d);
                classified.add(ClassifiedGroup.of(g, d.type()));
            } else {
                classified.add(ClassifiedGroup.matched(g));
            }
        }
        return new Result(groups, discrepancies, classified);
    }

    record Result(List<MatchGroup> groups, List<Discrepancy> discrepancies, List<ClassifiedGroup> classified) {
        Discrepancy only() {
            if (discrepancies.size() != 1) {
                throw new AssertionError("expected exactly 1 discrepancy, got " + discrepancies);
            }
            return discrepancies.get(0);
        }
    }
}
