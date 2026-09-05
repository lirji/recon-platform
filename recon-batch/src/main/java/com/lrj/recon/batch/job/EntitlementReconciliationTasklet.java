package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.domain.model.ReconRun;
import com.lrj.recon.core.domain.model.ReconRunStatus;
import com.lrj.recon.batch.service.EntitlementReconStore;
import com.lrj.recon.entitlement.model.EntitlementDiscrepancy;
import com.lrj.recon.entitlement.service.EntitlementDiscrepancyClassifier;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import java.time.Instant;
import java.util.List;

/**
 * 非金额权益对账任务：按 tenant + 事件时间窗口分页扫描三份 ODS，用数量/状态模型判差。
 * 整条链路不构造币种或金额，差异投影显式标记 {@code measure_kind=QUANTITY}。
 */
public class EntitlementReconciliationTasklet implements Tasklet {

    private static final int ISSUE_PAGE_SIZE = 500;
    private final EntitlementReconStore store;
    private final ReconRunRepository runs;
    private final ReconJobContext context;
    private final EntitlementDiscrepancyClassifier classifier = new EntitlementDiscrepancyClassifier();

    public EntitlementReconciliationTasklet(EntitlementReconStore store, ReconRunRepository runs,
                                            ReconJobContext context) {
        this.store = store;
        this.runs = runs;
        this.context = context;
    }

    /** 用 issue_id keyset 分页，避免把全租户或整账期对象一次载入内存。 */
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String lastIssueId = "";
        int discrepancyCount = 0;
        while (true) {
            List<EntitlementReconStore.ReconCase> cases = store.nextPage(context.tenantId(),
                    context.matchWindowFrom(), context.matchWindowTo(), lastIssueId, ISSUE_PAGE_SIZE);
            if (cases.isEmpty()) break;
            for (EntitlementReconStore.ReconCase reconCase : cases) {
                EntitlementDiscrepancy discrepancy = classifier.classify(reconCase.group());
                if (!discrepancy.clean()) {
                    store.insertDiscrepancies(context.runId(), context.scenarioCode(), reconCase,
                            discrepancy, Instant.now());
                    discrepancyCount += discrepancy.types().size();
                }
            }
            lastIssueId = cases.getLast().group().issueId();
            if (cases.size() < ISSUE_PAGE_SIZE) break;
        }
        finishRun(discrepancyCount == 0);
        return RepeatStatus.FINISHED;
    }

    private void finishRun(boolean clean) {
        ReconRun run = runs.find(context.runId()).orElseThrow();
        if (run.status() == ReconRunStatus.LOADING) {
            runs.save(run.toMatching(), run.revision());
        }
        ReconRun matching = runs.find(context.runId()).orElseThrow();
        ReconRun terminal = clean ? matching.complete() : matching.markImbalance();
        runs.save(terminal.toBuilder().finishedAt(Instant.now()).build(), matching.revision());
    }

}
