package com.lrj.recon.rules.drools;

import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchGroup;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.SortMergeJoiner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Drools 测试夹具: 造记录 → sort-merge join → MatchGroup (镜像 recon-core 的 ReconFixtures)。 */
final class DroolsTestFixtures {

    static final String USD = "USD";

    private DroolsTestFixtures() {
    }

    static ReconRecord rec(Side side, SourceRole role, String key, String ccy, long amountMinor, EntryType et) {
        return ReconRecord.builder()
                .recordId(side + ":" + role + ":" + key + ":" + amountMinor + ":" + et)
                .segmentId("SEG")
                .side(side)
                .sourceRole(role)
                .matchKey(MatchKey.of("key", key, 0))
                .groupKey(GroupKey.of("key", key))
                .bucket(0)
                .money(Money.of(ccy, amountMinor))
                .entryType(et)
                .rawRef(side + ":" + key + ":" + amountMinor)
                .build();
    }

    static ReconRecord left(String key, long amountMinor) {
        return rec(Side.LEFT, SourceRole.MARKETING, key, USD, amountMinor, EntryType.ISSUE);
    }

    static ReconRecord right(String key, long amountMinor) {
        return rec(Side.RIGHT, SourceRole.ACCOUNTING, key, USD, amountMinor, EntryType.ISSUE);
    }

    static ReconRecord rightCcy(String key, String ccy, long amountMinor) {
        return rec(Side.RIGHT, SourceRole.ACCOUNTING, key, ccy, amountMinor, EntryType.ISSUE);
    }

    /** 左营销 / 右账务, 无 spine (不触发 BRIDGE_BROKEN)。 */
    static EvaluationContext plainContext() {
        return EvaluationContext.builder()
                .runId("run-1").scenarioCode("scn").accountingPeriod("2026-08-17").segmentId("SEG")
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(null)
                .build();
    }

    /** 右账务为 spine: 左单边组 (右缺) → BRIDGE_BROKEN。 */
    static EvaluationContext spineContext() {
        return EvaluationContext.builder()
                .runId("run-1").scenarioCode("scn").accountingPeriod("2026-08-17").segmentId("SEG")
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(SourceRole.ACCOUNTING)
                .stageLabel("SEG1")
                .build();
    }

    /** 单桶跑 sort-merge join → 组列表。 */
    static List<MatchGroup> join(List<ReconRecord> lefts, List<ReconRecord> rights) {
        List<ReconRecord> l = new ArrayList<>(lefts);
        List<ReconRecord> r = new ArrayList<>(rights);
        l.sort(Comparator.comparing(ReconRecord::matchKey));
        r.sort(Comparator.comparing(ReconRecord::matchKey));
        List<MatchGroup> groups = new ArrayList<>();
        new SortMergeJoiner().join(l.iterator(), r.iterator(), groups::add);
        return groups;
    }

    /** 覆盖各主类型的混合组: clean / amount-mismatch / missing / extra / duplicate / group-sum / currency-mismatch。 */
    static List<MatchGroup> mixedPlainGroups() {
        List<ReconRecord> lefts = new ArrayList<>();
        List<ReconRecord> rights = new ArrayList<>();
        // clean 1:1
        lefts.add(left("K-clean", 1000));
        rights.add(right("K-clean", 1000));
        // amount mismatch
        lefts.add(left("K-amt", 1000));
        rights.add(right("K-amt", 900));
        // missing (右缺, 无 spine → MISSING)
        lefts.add(left("K-miss", 500));
        // extra (左缺 → EXTRA)
        rights.add(right("K-extra", 700));
        // duplicate (右同键两条)
        lefts.add(left("K-dup", 300));
        rights.add(right("K-dup", 300));
        rights.add(right("K-dup", 300));
        // group sum mismatch (多行左, 和 != 右)
        lefts.add(left("K-gsm", 400));
        lefts.add(left("K-gsm", 400));
        rights.add(right("K-gsm", 900));
        // currency mismatch
        lefts.add(left("K-ccy", 1000));
        rights.add(rightCcy("K-ccy", "EUR", 1000));
        return join(lefts, rights);
    }

    /** BRIDGE_BROKEN: 右账务(spine)缺, 左单边组。 */
    static List<MatchGroup> bridgeBrokenGroups() {
        return join(List.of(left("K-bridge", 800)), List.of());
    }
}
