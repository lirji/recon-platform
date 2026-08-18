package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscrepancyClassifierTest {

    private final EvaluationContext plain = ReconFixtures.plainContext();

    @Test
    void amount_mismatch_when_both_present_differ() {
        ReconFixtures.Result r = ReconFixtures.run(plain,
                List.of(ReconFixtures.left("K1", 100)),
                List.of(ReconFixtures.right("K1", 90)));
        Discrepancy d = r.only();
        assertThat(d.type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
        assertThat(d.expectedAmountMinor()).isEqualTo(100L);
        assertThat(d.actualAmountMinor()).isEqualTo(90L);
        assertThat(d.deltaAmountMinor()).isEqualTo(10L);
        assertThat(d.currency()).isEqualTo(ReconFixtures.USD);
        assertThat(d.fingerprint()).hasSize(64);
    }

    @Test
    void missing_when_left_only_and_not_spine() {
        ReconFixtures.Result r = ReconFixtures.run(plain,
                List.of(ReconFixtures.left("K1", 100)),
                List.of());
        assertThat(r.only().type()).isEqualTo(DiscrepancyType.MISSING);
        assertThat(r.only().expectedAmountMinor()).isEqualTo(100L);
    }

    @Test
    void extra_when_right_only_and_not_spine() {
        ReconFixtures.Result r = ReconFixtures.run(plain,
                List.of(),
                List.of(ReconFixtures.right("K1", 100)));
        assertThat(r.only().type()).isEqualTo(DiscrepancyType.EXTRA);
        assertThat(r.only().actualAmountMinor()).isEqualTo(100L);
    }

    @Test
    void duplicate_when_exact_copies_on_left() {
        ReconFixtures.Result r = ReconFixtures.run(plain,
                List.of(ReconFixtures.left("K1", 100), ReconFixtures.left("K1", 100)),
                List.of(ReconFixtures.right("K1", 100)));
        assertThat(r.only().type()).isEqualTo(DiscrepancyType.DUPLICATE);
    }

    @Test
    void group_sum_mismatch_when_multiline_sum_differs() {
        List<ReconRecord> lefts = List.of(
                ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE).build(),
                ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", -30, EntryType.REFUND).build());
        ReconFixtures.Result r = ReconFixtures.run(plain, lefts, List.of(ReconFixtures.right("K1", 100)));
        Discrepancy d = r.only();
        assertThat(d.type()).isEqualTo(DiscrepancyType.GROUP_SUM_MISMATCH);
        assertThat(d.expectedAmountMinor()).isEqualTo(70L);   // 左组 signed 和
        assertThat(d.actualAmountMinor()).isEqualTo(100L);
        assertThat(d.absDeltaMinor()).isEqualTo(30L);
    }

    @Test
    void currency_mismatch_short_circuits_numeric_compare() {
        ReconFixtures.Result r = ReconFixtures.run(plain,
                List.of(ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE).build()),
                List.of(ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "K1", "EUR", 100, EntryType.ISSUE).build()));
        Discrepancy d = r.only();
        assertThat(d.type()).isEqualTo(DiscrepancyType.CURRENCY_MISMATCH);
        assertThat(d.currency()).isNull();
    }

    @Test
    void status_mismatch_when_amounts_equal_but_status_differs() {
        ReconRecord l = ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE)
                .bizStatus("PAID").postingTime(ReconFixtures.DAY1).build();
        ReconRecord rr = ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "K1", "USD", 100, EntryType.ISSUE)
                .bizStatus("PENDING").postingTime(ReconFixtures.DAY1).build();
        ReconFixtures.Result r = ReconFixtures.run(plain, List.of(l), List.of(rr));
        assertThat(r.only().type()).isEqualTo(DiscrepancyType.STATUS_MISMATCH);
    }

    @Test
    void timing_when_posting_crosses_day_within_window() {
        ReconRecord l = ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE)
                .bizStatus("PAID").postingTime(ReconFixtures.DAY1).build();
        ReconRecord rr = ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "K1", "USD", 100, EntryType.ISSUE)
                .bizStatus("PAID").postingTime(ReconFixtures.DAY2).build();
        ReconFixtures.Result r = ReconFixtures.run(plain, List.of(l), List.of(rr));
        assertThat(r.only().type()).isEqualTo(DiscrepancyType.TIMING);
    }

    @Test
    void amount_mismatch_wins_over_status_when_both_conditions_hold() {
        // 端到端择优守卫: 金额不一致 AND 状态不一致同时成立时, 须发高优先级 AMOUNT_MISMATCH。
        // 若有人把 STATUS 分支移到 AMOUNT 之前, 本用例转红 (precedence 数值断言拦不住此漂移)。
        ReconRecord l = ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE)
                .bizStatus("PAID").postingTime(ReconFixtures.DAY1).build();
        ReconRecord rr = ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "K1", "USD", 90, EntryType.ISSUE)
                .bizStatus("PENDING").postingTime(ReconFixtures.DAY1).build();
        ReconFixtures.Result r = ReconFixtures.run(plain, List.of(l), List.of(rr));
        assertThat(r.only().type()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);
    }

    @Test
    void status_wins_over_timing_when_both_conditions_hold() {
        // 端到端择优守卫: 状态不一致 AND 跨日同时成立时, 须发高优先级 STATUS_MISMATCH。
        // 若有人把 TIMING 分支移到 STATUS 之前, 本用例转红。
        ReconRecord l = ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE)
                .bizStatus("PAID").postingTime(ReconFixtures.DAY1).build();
        ReconRecord rr = ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "K1", "USD", 100, EntryType.ISSUE)
                .bizStatus("PENDING").postingTime(ReconFixtures.DAY2).build();
        ReconFixtures.Result r = ReconFixtures.run(plain, List.of(l), List.of(rr));
        assertThat(r.only().type()).isEqualTo(DiscrepancyType.STATUS_MISMATCH);
    }

    @Test
    void bridge_broken_stage1_when_accounting_spine_missing_on_right() {
        // SEG1: 左营销 / 右账务(spine); 账务缺 → BRIDGE_BROKEN 段1 (压制 MISSING)
        EvaluationContext seg1 = EvaluationContext.builder()
                .runId("run-1").scenarioCode("scn").accountingPeriod("2026-08-17").segmentId("SEG1_MKT_ACCT")
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(SourceRole.ACCOUNTING)
                .stageLabel("SEG1").build();
        ReconFixtures.Result r = ReconFixtures.run(seg1,
                List.of(ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE).build()),
                List.of());
        Discrepancy d = r.only();
        assertThat(d.type()).isEqualTo(DiscrepancyType.BRIDGE_BROKEN);
        assertThat(d.bridgeBreakStage()).isEqualTo("SEG1");
    }

    @Test
    void bridge_broken_stage2_when_accounting_spine_missing_on_left() {
        // SEG2: 左账务(spine) / 右渠道; 账务缺 → BRIDGE_BROKEN 段2 (压制 EXTRA)
        EvaluationContext seg2 = EvaluationContext.builder()
                .runId("run-1").scenarioCode("scn").accountingPeriod("2026-08-17").segmentId("SEG2_ACCT_CHANNEL")
                .leftRole(SourceRole.ACCOUNTING).rightRole(SourceRole.CHANNEL).spineRole(SourceRole.ACCOUNTING)
                .stageLabel("SEG2").build();
        ReconFixtures.Result r = ReconFixtures.run(seg2,
                List.of(),
                List.of(ReconFixtures.base(Side.RIGHT, SourceRole.CHANNEL, "K1", "USD", 100, EntryType.ISSUE).build()));
        Discrepancy d = r.only();
        assertThat(d.type()).isEqualTo(DiscrepancyType.BRIDGE_BROKEN);
        assertThat(d.bridgeBreakStage()).isEqualTo("SEG2");
    }

    @Test
    void clean_match_produces_no_discrepancy() {
        ReconFixtures.Result r = ReconFixtures.run(plain,
                List.of(ReconFixtures.left("K1", 100)),
                List.of(ReconFixtures.right("K1", 100)));
        assertThat(r.discrepancies()).isEmpty();
    }

    @Test
    void precedence_matches_design_ordering() {
        // BRIDGE_BROKEN > CURRENCY_MISMATCH > DUPLICATE/EXTRA > GROUP_SUM > AMOUNT > STATUS > TIMING > MISSING
        assertThat(DiscrepancyType.BRIDGE_BROKEN.precedence())
                .isLessThan(DiscrepancyType.CURRENCY_MISMATCH.precedence());
        assertThat(DiscrepancyType.CURRENCY_MISMATCH.precedence())
                .isLessThan(DiscrepancyType.DUPLICATE.precedence());
        assertThat(DiscrepancyType.DUPLICATE.precedence())
                .isLessThan(DiscrepancyType.GROUP_SUM_MISMATCH.precedence());
        assertThat(DiscrepancyType.GROUP_SUM_MISMATCH.precedence())
                .isLessThan(DiscrepancyType.AMOUNT_MISMATCH.precedence());
        assertThat(DiscrepancyType.AMOUNT_MISMATCH.precedence())
                .isLessThan(DiscrepancyType.STATUS_MISMATCH.precedence());
        assertThat(DiscrepancyType.STATUS_MISMATCH.precedence())
                .isLessThan(DiscrepancyType.TIMING.precedence());
        assertThat(DiscrepancyType.TIMING.precedence())
                .isLessThan(DiscrepancyType.MISSING.precedence());
        assertThat(DiscrepancyType.MISSING.precedence())
                .isLessThan(DiscrepancyType.FX_RATE_DIFF.precedence());
        assertThat(DiscrepancyType.FX_RATE_DIFF.judgedInMvp()).isFalse();
    }

    @Test
    void bridge_broken_suppresses_missing_only_for_spine_side() {
        // 同为左侧缺右侧: spine=ACCOUNTING 时 K1(右账务缺) → BRIDGE_BROKEN;
        // 若右侧角色非 spine → MISSING (对照组已由 missing_when_left_only 覆盖, 此处确认压制不误伤)
        EvaluationContext seg1 = EvaluationContext.builder()
                .runId("run-1").scenarioCode("scn").accountingPeriod("2026-08-17").segmentId("SEG1")
                .leftRole(SourceRole.MARKETING).rightRole(SourceRole.ACCOUNTING).spineRole(SourceRole.ACCOUNTING)
                .stageLabel("SEG1").build();
        ReconFixtures.Result r = ReconFixtures.run(seg1,
                List.of(ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE).build()),
                List.of());
        assertThat(r.only().type()).isEqualTo(DiscrepancyType.BRIDGE_BROKEN);
    }
}
