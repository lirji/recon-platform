package com.lrj.recon.core;

import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.EvaluationContext;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.ReconReport;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.ConservationChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名金额专项 (设计修补④): 组内混 ISSUE/REFUND/REVERSAL, 使 signed 和为 0 与为负两场景,
 * 守恒不假阳性; GROUP_SUM 的守恒贡献用左组 signed 和 (可为 0/负), 等式仍闭合。
 */
class SignedAmountConservationTest {

    private final ConservationChecker checker = new ConservationChecker();
    private final EvaluationContext ctx = ReconFixtures.plainContext();

    private ReconRecord l(String key, long amt, EntryType et) {
        return ReconFixtures.base(Side.LEFT, SourceRole.MARKETING, key, "USD", amt, et).build();
    }

    private ReconRecord r(String key, long amt, EntryType et) {
        return ReconFixtures.base(Side.RIGHT, SourceRole.ACCOUNTING, key, "USD", amt, et).build();
    }

    @Test
    void zero_net_group_is_matched_not_false_group_sum() {
        // 左: ISSUE +100, REFUND -100 (净 0); 右: ISSUE +100, REVERSAL -100 (净 0)
        List<ReconRecord> lefts = List.of(l("K1", 100, EntryType.ISSUE), l("K1", -100, EntryType.REFUND));
        List<ReconRecord> rights = List.of(r("K1", 100, EntryType.ISSUE), r("K1", -100, EntryType.REVERSAL));

        ReconFixtures.Result result = ReconFixtures.run(ctx, lefts, rights);
        assertThat(result.discrepancies()).isEmpty();   // 无假阳性

        ReconReport report = checker.checkSingle("run-1", "SEG", result.classified());
        assertThat(report.expectedTotalMinor()).isZero();
        assertThat(report.matchedAmountMinor()).isZero();
        assertThat(report.balanced()).isTrue();
    }

    @Test
    void negative_net_group_matches_and_conserves() {
        // 左右净额均为 -50 → 匹配, 守恒带负额闭合
        List<ReconRecord> lefts = List.of(l("K1", 100, EntryType.ISSUE), l("K1", -150, EntryType.REFUND));
        List<ReconRecord> rights = List.of(r("K1", 100, EntryType.ISSUE), r("K1", -150, EntryType.REFUND));

        ReconFixtures.Result result = ReconFixtures.run(ctx, lefts, rights);
        assertThat(result.discrepancies()).isEmpty();

        ReconReport report = checker.checkSingle("run-1", "SEG", result.classified());
        assertThat(report.expectedTotalMinor()).isEqualTo(-50L);
        assertThat(report.matchedAmountMinor()).isEqualTo(-50L);
        assertThat(report.leftResidualMinor()).isZero();
        assertThat(report.rightResidualMinor()).isZero();
        assertThat(report.balanced()).isTrue();
    }

    @Test
    void negative_net_group_sum_mismatch_still_conserves() {
        // 左净 -50, 右净 -30 → GROUP_SUM_MISMATCH; 左组和(-50)进左口径, 右额(-30)进右口径 matched
        List<ReconRecord> lefts = List.of(l("K1", 100, EntryType.ISSUE), l("K1", -150, EntryType.REFUND));
        List<ReconRecord> rights = List.of(r("K1", 100, EntryType.ISSUE), r("K1", -130, EntryType.REFUND));

        ReconFixtures.Result result = ReconFixtures.run(ctx, lefts, rights);
        assertThat(result.only().type()).isEqualTo(DiscrepancyType.GROUP_SUM_MISMATCH);

        ReconReport report = checker.checkSingle("run-1", "SEG", result.classified());
        assertThat(report.expectedTotalMinor()).isEqualTo(-50L);
        assertThat(report.groupSumMismatchMinor()).isEqualTo(-50L);
        assertThat(report.rightSideTotalMinor()).isEqualTo(-30L);
        assertThat(report.leftResidualMinor()).isZero();
        assertThat(report.rightResidualMinor()).isZero();
        assertThat(report.balanced()).isTrue();
    }
}
