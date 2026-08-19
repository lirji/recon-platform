package com.lrj.recon.batch.web;

import com.lrj.recon.core.application.port.out.ReversalSuggestionRepository;
import com.lrj.recon.core.domain.model.ReversalSuggestion;
import com.lrj.recon.workflow.flowable.ReversalApprovalWorkflow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B5 · 冲正审批 API(配置驱动,Flowable)。读待办 = {@code recon.read};提交/审批 = {@code recon.dispose}
 * (由 CasdoorSecurityConfig matcher 约束)。工作流未启用({@code recon.workflow.flowable.enabled=false})时
 * 各接口 fail-fast(绝不静默无操作)。
 */
@RestController
@RequestMapping("/recon/reversal-approvals")
public class ReversalApprovalController {

    private final ObjectProvider<ReversalApprovalWorkflow> workflow;
    private final ReversalSuggestionRepository reversals;

    public ReversalApprovalController(ObjectProvider<ReversalApprovalWorkflow> workflow,
                                      ReversalSuggestionRepository reversals) {
        this.workflow = workflow;
        this.reversals = reversals;
    }

    /** 待审批列表(富化):每条待办按 reversalId join reversal_suggestion 补金额/币种/状态/血缘。 */
    @GetMapping
    public List<PendingApprovalView> pending() {
        return workflow().listPending().stream().map(this::enrich).toList();
    }

    @PostMapping("/submit")
    public String submit(@RequestParam("reversalId") String reversalId) {
        return workflow().submit(reversalId);
    }

    @PostMapping("/{taskId}/decide")
    public void decide(@PathVariable("taskId") String taskId,
                       @RequestParam("approved") boolean approved,
                       @RequestParam(name = "operator", required = false) String operator,
                       @RequestParam(name = "note", required = false) String note) {
        workflow().decide(taskId, approved, operator, note);
    }

    /** join miss(建议不存在)时业务字段留 null,前端显「—」;金额转 minor 十进制字符串。 */
    private PendingApprovalView enrich(ReversalApprovalWorkflow.PendingApproval p) {
        ReversalSuggestion r = p.reversalId() == null ? null : reversals.find(p.reversalId()).orElse(null);
        return new PendingApprovalView(
                p.taskId(), p.reversalId(), p.createdAt(),
                r == null ? null : Long.toString(r.suggestedAmountMinor()),
                r == null ? null : r.currency(),
                r == null ? null : r.status().name(),
                r == null ? null : r.groupKey(),
                r == null ? null : r.runId());
    }

    private ReversalApprovalWorkflow workflow() {
        ReversalApprovalWorkflow w = workflow.getIfAvailable();
        if (w == null) {
            throw new IllegalStateException(
                    "reversal approval workflow is not enabled; set recon.workflow.flowable.enabled=true");
        }
        return w;
    }
}
