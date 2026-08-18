package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ConservationPartialRepository;
import com.lrj.recon.core.application.port.out.ReconReportRepository;
import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.domain.model.ConflictException;
import com.lrj.recon.core.domain.model.ConservationPartial;
import com.lrj.recon.core.domain.model.ReconReport;
import com.lrj.recon.core.domain.model.ReconRun;
import com.lrj.recon.core.domain.model.ReconRunStatus;
import com.lrj.recon.core.domain.model.RunKey;
import com.lrj.recon.core.domain.service.ConservationMerger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #9 覆盖 REPORT_IMBALANCE 终态分支 (§8 构造性守恒的回归护栏, 正常路径不可达)。
 *
 * <p>守恒 residual≡0 是构造性恒等, 正常永远 balanced=true, 故 markImbalance 分支平时构造性不可达。本测试
 * <b>故意</b>喂给汇总步一份"子项与 expected_total 不自洽"的 {@link ConservationPartial}
 * (expected=1000 但各子项全 0 → leftResidual=1000≠0), 驱动 {@link ReportTasklet} 走 markImbalance,
 * 断言 Run 被置 {@link ReconRunStatus#REPORT_IMBALANCE} —— 证明门禁真能触发 (若哪天桶路由被改坏/溢出, 会被抓住)。
 */
class ReportTaskletImbalanceTest {

    private static final String RUN = "run-imbalance";
    private static final String SEG = "SEG1_MKT_ACCT";

    @Test
    void unbalancedPartialDrivesRunToReportImbalance() {
        CapturingRunRepo runs = new CapturingRunRepo(matchingRun());
        CapturingReportRepo reports = new CapturingReportRepo();
        // 不自洽的局部结果: 应对总额 1000, 但没有任何子项承接 → leftResidual = 1000 ≠ 0 → balanced=false
        ConservationPartial unbalanced = ConservationPartial.builder()
                .runId(RUN).segmentId(SEG).bucket(0).subIndex(-1).currency("USD")
                .expectedTotalMinor(1000)
                .build();
        ConservationPartialRepository partials = new ConservationPartialRepository() {
            @Override public void savePartials(Iterable<ConservationPartial> ps) { throw new UnsupportedOperationException(); }
            @Override public List<ConservationPartial> listByRun(String runId) { return List.of(unbalanced); }
            @Override public int deleteStaleBucketPartials(String r, String s, int b, int si, int f) { throw new UnsupportedOperationException(); }
            @Override public int deleteByRunBounded(String runId, int limit) { throw new UnsupportedOperationException(); }
        };

        ReconJobContext ctx = new ReconJobContext(RUN, "MARKETING_3WAY", "2026-08-17", 1,
                Instant.parse("2026-08-17T23:00:00Z"), Instant.parse("2026-08-17T00:00:00Z"),
                Instant.parse("2026-08-18T23:59:59Z"), 8, 1);

        ReportTasklet tasklet = new ReportTasklet(runs, reports, partials, new ConservationMerger(), ctx,
                runId -> { /* no-op failure gate */ });

        tasklet.execute(null, null);

        // 报表落库为不平衡
        assertThat(reports.saved).hasSize(1);
        assertThat(reports.saved.get(0).balanced()).isFalse();
        assertThat(reports.saved.get(0).leftResidualMinor()).isEqualTo(1000);
        // 终态被置 REPORT_IMBALANCE (门禁触发)
        assertThat(runs.current.status()).isEqualTo(ReconRunStatus.REPORT_IMBALANCE);
        assertThat(runs.current.finishedAt()).isNotNull();
    }

    private static ReconRun matchingRun() {
        Instant now = Instant.now();
        return ReconRun.builder()
                .runId(RUN)
                .key(RunKey.of("MARKETING_3WAY", "2026-08-17", 1))
                .cutoffTime(now).matchWindowFrom(now).matchWindowTo(now)
                .bucketCount(8)
                .status(ReconRunStatus.MATCHING)   // 已 MATCHING → 跳过 LOADING→MATCHING, 直接进终态判定
                .revision(3)
                .createdAt(now).updatedAt(now).startedAt(now)
                .build();
    }

    /** 记录当前 Run 状态的假仓储 (find 返回当前, save 更新当前)。 */
    private static final class CapturingRunRepo implements ReconRunRepository {
        private ReconRun current;

        CapturingRunRepo(ReconRun initial) {
            this.current = initial;
        }

        @Override public void claim(ReconRun run) { throw new UnsupportedOperationException(); }
        @Override public Optional<ReconRun> find(String runId) { return Optional.of(current); }
        @Override public void save(ReconRun run, long expectedRevision) throws ConflictException { this.current = run; }
    }

    /** 捕获 saveAll 的假报表仓储。 */
    private static final class CapturingReportRepo implements ReconReportRepository {
        private final List<ReconReport> saved = new ArrayList<>();

        @Override public void saveAll(Iterable<ReconReport> rs) { rs.forEach(saved::add); }
        @Override public List<ReconReport> listByRun(String runId) { return List.copyOf(saved); }
        @Override public int deleteByRunBounded(String runId, int limit) { throw new UnsupportedOperationException(); }
    }
}
