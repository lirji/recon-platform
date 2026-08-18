package com.lrj.recon.batch.web;

import com.lrj.recon.batch.job.ReconLaunchService;
import com.lrj.recon.batch.service.ManualClearingService;
import com.lrj.recon.batch.service.NotFoundException;
import com.lrj.recon.core.application.port.out.ReconReportRepository;
import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.domain.model.DiscrepancyDisposition;
import com.lrj.recon.core.domain.model.ReconReport;
import com.lrj.recon.core.domain.model.ReconRun;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对账 REST 接口 (设计 §11 M5): 发起 Run / 重跑 / 人工核销 / 报表查询。
 *
 * <p>薄编排层, 只依赖<b>纯服务</b> ({@link ReconLaunchService} / {@link ManualClearingService}) 与领域端口
 * (无 Spring Batch / JDBC 直接依赖, 满足 ArchUnit)。每接口做参数校验 (空值 → 400)、幂等 (人工核销状态机幂等短路)、
 * 乐观锁 (expectedVersion → 409)。<b>防越权</b>: MVP 简化鉴权 (operator 由请求体带), 留接口位; 阶段二接 auth-platform
 * 从鉴权上下文取操作者并做行级授权。
 */
@RestController
@RequestMapping("/recon")
public class DiscrepancyController {

    private final ReconLaunchService launchService;
    private final ManualClearingService manualClearing;
    private final ReconReportRepository reports;
    private final ReconRunRepository runs;

    public DiscrepancyController(ReconLaunchService launchService,
                                 ManualClearingService manualClearing,
                                 ReconReportRepository reports,
                                 ReconRunRepository runs) {
        this.launchService = launchService;
        this.manualClearing = manualClearing;
        this.reports = reports;
        this.runs = runs;
    }

    /** 发起一次对账 Run (序号原子分配, 无竞态)。 */
    @PostMapping("/runs")
    public ReconLaunchService.LaunchResult launchRun(@RequestBody LaunchRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body must not be empty");
        }
        return launchService.launch(new ReconLaunchService.LaunchCommand(
                req.scenarioCode(), req.accountingPeriod(), req.jobName(), req.bucketCount(),
                null, null, null));
    }

    /** 重跑既有 Run (同 runId, 新 attempt; 保留人工痕迹)。 */
    @PostMapping("/runs/{id}/rerun")
    public ReconLaunchService.LaunchResult rerun(@PathVariable("id") String runId) {
        return launchService.rerun(runId);
    }

    /** 人工核销: OPEN→RESOLVED (幂等 / 乐观锁 / 409)。 */
    @PostMapping("/discrepancies/{id}/resolve")
    public DispositionResponse resolve(@PathVariable("id") String discrepancyId, @RequestBody ClearRequest req) {
        ClearRequest r = requireBody(req);
        return DispositionResponse.of(
                manualClearing.resolve(discrepancyId, r.operator(), r.note(), r.expectedVersion()));
    }

    /** 人工关闭: OPEN/RESOLVED→CLOSED (幂等 / 乐观锁 / 409)。 */
    @PostMapping("/discrepancies/{id}/close")
    public DispositionResponse close(@PathVariable("id") String discrepancyId, @RequestBody ClearRequest req) {
        ClearRequest r = requireBody(req);
        return DispositionResponse.of(
                manualClearing.close(discrepancyId, r.operator(), r.note(), r.expectedVersion()));
    }

    /** 查询 Run 报表 (勾稽双向守恒 + 终态)。 */
    @GetMapping("/runs/{id}/report")
    public ReportResponse report(@PathVariable("id") String runId) {
        ReconRun run = runs.find(runId)
                .orElseThrow(() -> new NotFoundException("run not found: " + runId));
        List<ReportRow> rows = reports.listByRun(runId).stream().map(ReportRow::of).toList();
        boolean balanced = !rows.isEmpty() && rows.stream().allMatch(ReportRow::balanced);
        return new ReportResponse(runId, run.status().name(), balanced, rows);
    }

    private static ClearRequest requireBody(ClearRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body must not be empty");
        }
        return req;
    }

    // ==================== DTO ====================

    /** 发起 Run 请求体。窗口/cutoff 由账期派生 (MVP 不暴露, 阶段二可加)。 */
    public record LaunchRequest(String scenarioCode, String accountingPeriod, String jobName, Integer bucketCount) {
    }

    /** 人工核销请求体 (operator 必填; note 可选; expectedVersion 乐观锁, null=不校验)。 */
    public record ClearRequest(String operator, String note, Integer expectedVersion) {
    }

    /** 处置响应。 */
    public record DispositionResponse(String fingerprint, String segmentId, String status,
                                      String operator, String note, int version, String lastSeenRunId) {
        static DispositionResponse of(DiscrepancyDisposition d) {
            return new DispositionResponse(d.fingerprint(), d.segmentId(), d.status().name(),
                    d.operator(), d.note(), d.version(), d.lastSeenRunId());
        }
    }

    /** 报表响应。 */
    public record ReportResponse(String runId, String status, boolean balanced, List<ReportRow> reports) {
    }

    /** 单段/币种报表行。 */
    public record ReportRow(String segmentId, String currency, long expectedTotalMinor, long matchedAmountMinor,
                            long amountMismatchMinor, long missingMinor, long duplicateMinor, long extraMinor,
                            long timingMinor, long statusMismatchMinor, long currencyMismatchMinor,
                            long groupSumMismatchMinor, long bridgeBrokenMinor, long rightSideTotalMinor,
                            long leftResidualMinor, long rightResidualMinor, boolean balanced) {
        static ReportRow of(ReconReport r) {
            return new ReportRow(r.segmentId(), r.currency(), r.expectedTotalMinor(), r.matchedAmountMinor(),
                    r.amountMismatchMinor(), r.missingMinor(), r.duplicateMinor(), r.extraMinor(),
                    r.timingMinor(), r.statusMismatchMinor(), r.currencyMismatchMinor(),
                    r.groupSumMismatchMinor(), r.bridgeBrokenMinor(), r.rightSideTotalMinor(),
                    r.leftResidualMinor(), r.rightResidualMinor(), r.balanced());
        }
    }
}
