package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.StreamingReconciler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B8 · 流式对账内核:增量 accept + 窗口 flush 复用批处理同一分类内核,结果与批一致;干净匹配不出结果;
 * null 键逐条单边路由。
 */
class StreamingReconcilerTest {

    private static ReconRecord rec(Side side, SourceRole role, String matchKey, String groupKey,
                                   long amount, String rawRef) {
        return ReconRecord.builder()
                .recordId(side + ":" + rawRef).segmentId("SEG").side(side).sourceRole(role)
                .matchKey(matchKey == null ? null : MatchKey.of("key", matchKey, 0))
                .groupKey(GroupKey.of("key", groupKey))
                .bucket(0).money(Money.of("USD", amount)).entryType(EntryType.ISSUE).rawRef(rawRef)
                .build();
    }

    private static EvaluationContext plainContext() {
        return EvaluationContext.builder()
                .runId("run-1").scenarioCode("scn").accountingPeriod("2026-08-17").segmentId("SEG")
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(null)
                .build();
    }

    @Test
    void incremental_feed_then_flush_classifies_like_batch() {
        StreamingReconciler s = new StreamingReconciler();
        // clean 1:1
        s.accept(rec(Side.LEFT, SourceRole.MARKETING, "K-clean", "K-clean", 1000, "l-clean"));
        s.accept(rec(Side.RIGHT, SourceRole.ACCOUNTING, "K-clean", "K-clean", 1000, "r-clean"));
        // amount mismatch
        s.accept(rec(Side.LEFT, SourceRole.MARKETING, "K-amt", "K-amt", 1000, "l-amt"));
        s.accept(rec(Side.RIGHT, SourceRole.ACCOUNTING, "K-amt", "K-amt", 900, "r-amt"));
        // missing (left only, no spine → MISSING)
        s.accept(rec(Side.LEFT, SourceRole.MARKETING, "K-miss", "K-miss", 500, "l-miss"));

        assertThat(s.pendingGroups()).isEqualTo(3);
        var discrepancies = s.flush(plainContext());

        assertThat(discrepancies).extracting(Discrepancy::type)
                .containsExactlyInAnyOrder(DiscrepancyType.AMOUNT_MISMATCH, DiscrepancyType.MISSING);
        assertThat(s.pendingGroups()).isZero(); // flush 清空状态
    }

    @Test
    void null_match_key_records_route_as_separate_single_sided_groups() {
        StreamingReconciler s = new StreamingReconciler();
        // 同 group_key 下两条 null-key 左记录 → 两个独立单边组(各 MISSING),不并入他键
        s.accept(rec(Side.LEFT, SourceRole.MARKETING, null, "G1", 100, "l-n1"));
        s.accept(rec(Side.LEFT, SourceRole.MARKETING, null, "G1", 200, "l-n2"));

        assertThat(s.pendingGroups()).isEqualTo(2);
        var discrepancies = s.flush(plainContext());
        assertThat(discrepancies).extracting(Discrepancy::type)
                .containsExactly(DiscrepancyType.MISSING, DiscrepancyType.MISSING);
    }

    @Test
    void multi_line_same_key_aggregates_into_one_group() {
        StreamingReconciler s = new StreamingReconciler();
        // 同 match_key 左两条(400 + -100 = 300)vs 右 500 → GROUP_SUM_MISMATCH(多行组)
        s.accept(rec(Side.LEFT, SourceRole.MARKETING, "K-g", "K-g", 400, "l-g1"));
        s.accept(rec(Side.LEFT, SourceRole.MARKETING, "K-g", "K-g", -100, "l-g2"));
        s.accept(rec(Side.RIGHT, SourceRole.ACCOUNTING, "K-g", "K-g", 500, "r-g"));

        assertThat(s.pendingGroups()).isEqualTo(1);
        var discrepancies = s.flush(plainContext());
        assertThat(discrepancies).extracting(Discrepancy::type)
                .containsExactly(DiscrepancyType.GROUP_SUM_MISMATCH);
    }
}
