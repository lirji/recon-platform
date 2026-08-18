package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.ReconReport;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.ConservationChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ConservationCheckerTest {

    private final ConservationChecker checker = new ConservationChecker();

    @Test
    void bidirectional_conservation_closes_across_mixed_types() {
        EvaluationContext ctx = ReconFixtures.plainContext();
        List<ReconRecord> lefts = List.of(
                ReconFixtures.left("M1", 100),  // matched
                ReconFixtures.left("A1", 100),  // amount mismatch (right 90)
                ReconFixtures.left("MS1", 50),  // missing (right none)
                ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "G1", "USD", 100, EntryType.ISSUE).build(),
                ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "G1", "USD", -30, EntryType.REFUND).build());
        List<ReconRecord> rights = List.of(
                ReconFixtures.right("M1", 100),
                ReconFixtures.right("A1", 90),
                ReconFixtures.right("E1", 40),   // extra (left none)
                ReconFixtures.right("G1", 100));

        ReconFixtures.Result result = ReconFixtures.run(ctx, lefts, rights);
        ReconReport report = checker.checkSingle("run-1", "SEG", result.classified());

        assertThat(report.currency()).isEqualTo(ReconFixtures.USD);
        assertThat(report.expectedTotalMinor()).isEqualTo(320L);   // 100+100+50+70
        assertThat(report.matchedAmountMinor()).isEqualTo(100L);   // 仅干净匹配 M1
        assertThat(report.missingMinor()).isEqualTo(50L);
        assertThat(report.amountMismatchMinor()).isEqualTo(100L);
        assertThat(report.groupSumMismatchMinor()).isEqualTo(70L);
        assertThat(report.extraMinor()).isEqualTo(40L);
        assertThat(report.rightSideTotalMinor()).isEqualTo(330L);  // 100+90+40+100

        assertThat(report.leftResidualMinor()).isZero();
        assertThat(report.rightResidualMinor()).isZero();
        assertThat(report.balanced()).isTrue();
    }

    @Test
    void multi_currency_buckets_never_cross_add() {
        EvaluationContext ctx = ReconFixtures.plainContext();
        List<ReconRecord> lefts = List.of(
                ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "U1", "USD", 100, EntryType.ISSUE).build(),
                ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "E1", "EUR", 200, EntryType.ISSUE).build());
        List<ReconRecord> rights = List.of(
                ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "U1", "USD", 100, EntryType.ISSUE).build(),
                ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "E1", "EUR", 200, EntryType.ISSUE).build());

        ReconFixtures.Result result = ReconFixtures.run(ctx, lefts, rights);
        Map<String, ReconReport> byCcy = index(checker.check("run-1", "SEG", result.classified()));

        assertThat(byCcy).containsOnlyKeys(ReconFixtures.USD, ReconFixtures.EUR);
        assertThat(byCcy.get(ReconFixtures.USD).expectedTotalMinor()).isEqualTo(100L);
        assertThat(byCcy.get(ReconFixtures.EUR).expectedTotalMinor()).isEqualTo(200L);
        assertThat(byCcy.get(ReconFixtures.USD).balanced()).isTrue();
        assertThat(byCcy.get(ReconFixtures.EUR).balanced()).isTrue();
    }

    @Test
    void currency_mismatch_splits_into_two_buckets_each_balanced() {
        EvaluationContext ctx = ReconFixtures.plainContext();
        List<ReconRecord> lefts = List.of(
                ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, "K1", "USD", 100, EntryType.ISSUE).build());
        List<ReconRecord> rights = List.of(
                ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, "K1", "EUR", 100, EntryType.ISSUE).build());

        ReconFixtures.Result result = ReconFixtures.run(ctx, lefts, rights);
        Map<String, ReconReport> byCcy = index(checker.check("run-1", "SEG", result.classified()));

        assertThat(byCcy).containsOnlyKeys(ReconFixtures.USD, ReconFixtures.EUR);

        ReconReport usd = byCcy.get(ReconFixtures.USD);
        assertThat(usd.expectedTotalMinor()).isEqualTo(100L);      // 左额落 USD 桶
        assertThat(usd.rightSideTotalMinor()).isZero();
        assertThat(usd.currencyMismatchMinor()).isEqualTo(100L);
        assertThat(usd.matchedAmountMinor()).isZero();             // 不计入 matched
        assertThat(usd.balanced()).isTrue();

        ReconReport eur = byCcy.get(ReconFixtures.EUR);
        assertThat(eur.rightSideTotalMinor()).isEqualTo(100L);     // 右额落 EUR 桶
        assertThat(eur.expectedTotalMinor()).isZero();
        assertThat(eur.currencyMismatchMinor()).isEqualTo(100L);
        assertThat(eur.balanced()).isTrue();
    }

    private static Map<String, ReconReport> index(List<ReconReport> reports) {
        return reports.stream().collect(Collectors.toMap(ReconReport::currency, Function.identity()));
    }
}
