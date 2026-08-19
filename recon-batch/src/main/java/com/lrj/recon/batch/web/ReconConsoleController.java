package com.lrj.recon.batch.web;

import com.lrj.recon.batch.service.ReconConsoleQueryRepository;
import com.lrj.recon.batch.service.ReconConsoleQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 对账管理台只读 API。写操作继续由 {@link DiscrepancyController} 提供。 */
@RestController
@RequestMapping("/recon")
public class ReconConsoleController {

    private final ReconConsoleQueryService queries;

    public ReconConsoleController(ReconConsoleQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/dashboard")
    public ReconConsoleQueryRepository.DashboardView dashboard() {
        return queries.dashboard();
    }

    @GetMapping("/runs")
    public ReconConsoleQueryRepository.PageResult<ReconConsoleQueryRepository.RunSummary> runs(
            @RequestParam(required = false) String scenarioCode,
            @RequestParam(required = false) String accountingPeriod,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.listRuns(scenarioCode, accountingPeriod, status, page, size);
    }

    @GetMapping("/runs/{id}")
    public ReconConsoleQueryRepository.RunDetail run(@PathVariable("id") String runId) {
        return queries.getRun(runId);
    }

    /** B1 三方合并 roll-up 摘要(只读派生,recon.read)。 */
    @GetMapping("/runs/{id}/three-way")
    public ReconConsoleQueryRepository.ThreeWayReport threeWay(@PathVariable("id") String runId) {
        return queries.threeWayRollup(runId);
    }

    /** A5/KI-6 数据质量护栏:函数性 refine 违规(同一 match_key 落多个 group_key)只读诊断(recon.read)。 */
    @GetMapping("/runs/{id}/refine-violations")
    public ReconConsoleQueryRepository.RefineViolationReport refineViolations(@PathVariable("id") String runId) {
        return queries.refineViolations(runId);
    }

    @GetMapping("/discrepancies")
    public ReconConsoleQueryRepository.PageResult<ReconConsoleQueryRepository.DiscrepancySummary> discrepancies(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String segmentId,
            @RequestParam(required = false) String currency,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.listDiscrepancies(runId, type, status, segmentId, currency, query, page, size);
    }

    @GetMapping("/discrepancies/{id}")
    public ReconConsoleQueryRepository.DiscrepancyDetail discrepancy(@PathVariable("id") String discrepancyId) {
        return queries.getDiscrepancy(discrepancyId);
    }
}
