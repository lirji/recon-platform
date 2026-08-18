package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ConservationPartialRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyRepository;
import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.application.port.out.ReconReportRepository;
import com.lrj.recon.batch.persistence.JdbcRecordRejectStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.IntSupplier;

/**
 * 重跑清理 (修补③, ADR-7): 分批删除机器结果, <b>每批独立事务</b>, 避免千万级大事务长锁。
 *
 * <p><b>只清机器产物</b>: {@code recon_record} (staging) + {@code recon_record_reject} (标准化拒绝行)
 * + {@code discrepancy(machine_result=1)}
 * + {@code recon_report_partial} (M3 局部守恒结果) + {@code recon_report} (勾稽报表)。<b>绝不触碰</b>
 * {@code discrepancy_disposition} / {@code reversal_suggestion} / {@code discrepancy_action} (人工痕迹永不被重跑删除)。
 * 局部结果幂等键含 bucket, 重跑桶集若变化, 旧桶残留会污染汇总, 故必须一并分批清 (再由 Step2 重写)。
 * {@code recon_report} 同理 (#5): reportStep 只对本次仍产出的 (segment,currency) upsert, 若重跑时某组数据整体消失,
 * 旧报表行不会被覆盖 → 孤儿陈旧金额残留, 故必须先分批删净再由 reportStep 重写。
 *
 * <p>每批用 {@link TransactionTemplate}({@code REQUIRES_NEW}) 独立提交: 循环 {@code deleteXxxBounded(limit)}
 * 直到返回 0 行, 每批 ≤ {@code batchLimit} 行, 与调用方 (prepareRunStep tasklet) 的事务解耦。
 */
@Service
public class ReconRerunService {

    private final ReconRecordRepository records;
    private final JdbcRecordRejectStore rejects;
    private final DiscrepancyRepository discrepancies;
    private final ConservationPartialRepository partials;
    private final ReconReportRepository reports;
    private final TransactionTemplate txTemplate;
    private final int batchLimit;

    public ReconRerunService(ReconRecordRepository records,
                             JdbcRecordRejectStore rejects,
                             DiscrepancyRepository discrepancies,
                             ConservationPartialRepository partials,
                             ReconReportRepository reports,
                             PlatformTransactionManager txManager,
                             @Value("${recon.rerun.batch-limit:10000}") int batchLimit) {
        this.records = records;
        this.rejects = rejects;
        this.discrepancies = discrepancies;
        this.partials = partials;
        this.reports = reports;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchLimit = batchLimit;
    }

    /** 分批清空某 Run 的 staging/reject、机器判差、局部守恒结果与勾稽报表。人工表零触碰。 */
    public void cleanBounded(String runId) {
        drain(() -> records.deleteByRunBounded(runId, batchLimit));
        drain(() -> rejects.deleteByRunBounded(runId, batchLimit));
        drain(() -> discrepancies.deleteOpenMachineByRunBounded(runId, batchLimit));
        drain(() -> partials.deleteByRunBounded(runId, batchLimit));
        drain(() -> reports.deleteByRunBounded(runId, batchLimit)); // #5: 清孤儿旧报表行
    }

    private void drain(IntSupplier deleteBatch) {
        int deleted;
        do {
            Integer n = txTemplate.execute(status -> deleteBatch.getAsInt());
            deleted = n == null ? 0 : n;
        } while (deleted > 0);
    }
}
