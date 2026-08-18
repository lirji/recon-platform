package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.ReconRunSeqRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ReconRunSeqRepository} 的 JDBC 实现 (修补⑦/ADR-12): 序号原子分配, scheduler 与 REST 同一路径无
 * {@code MAX+1} 竞态。
 *
 * <p><b>并发安全</b>: 分配在<b>单事务</b>内做"行级自增 + 读回": {@code UPDATE recon_run_seq SET next_seq=next_seq+1}
 * 对 {@code (scenario, period)} 行加行锁, 同事务紧接的 {@code SELECT next_seq} 读到本事务自增后的值 (锁持有至提交,
 * 并发分配串行化), 占用序号 = {@code next_seq - 1}。首个 (scenario, period) 无行时 INSERT 初值 (占用序号 1, next=2);
 * 并发首插撞 {@code PK} → {@link DuplicateKeyException} 回退重试走 UPDATE 路径。可移植 (H2/MySQL/PG 均行锁), 不依赖
 * MySQL 专属 {@code INSERT ... ON DUPLICATE KEY UPDATE}。
 */
@Repository
public class JdbcReconRunSeqStore implements ReconRunSeqRepository {

    private static final int MAX_ATTEMPTS = 32;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    public JdbcReconRunSeqStore(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.txTemplate = new TransactionTemplate(txManager);
        // REQUIRES_NEW: 分配自成短事务, 与调用方 (REST/scheduler) 事务解耦; 行锁尽早释放。
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public int nextSequence(String scenarioCode, String accountingPeriod) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                Integer seq = txTemplate.execute(status -> allocateOnce(scenarioCode, accountingPeriod));
                if (seq != null) {
                    return seq;
                }
            } catch (DuplicateKeyException raced) {
                // 必须在 TransactionTemplate 外捕获：让首插冲突事务先完整回滚，PG 才能安全重试。
            }
        }
        throw new IllegalStateException("could not allocate sequence for (" + scenarioCode + ", "
                + accountingPeriod + ") after " + MAX_ATTEMPTS + " attempts");
    }

    /** 单事务内分配: 返回占用序号; 首插并发撞 PK 返回 {@code null} 由外层重试。 */
    private Integer allocateOnce(String scenarioCode, String accountingPeriod) {
        int updated = jdbc.update(
                "UPDATE recon_run_seq SET next_seq = next_seq + 1 WHERE scenario_code = ? AND accounting_period = ?",
                scenarioCode, accountingPeriod);
        if (updated == 0) {
            // 首次分配: 占用序号 1, 下一个从 2 起。并发撞 PK 让整个短事务回滚，再由外层循环重试。
            jdbc.update(
                    "INSERT INTO recon_run_seq(scenario_code, accounting_period, next_seq) VALUES (?,?,?)",
                    scenarioCode, accountingPeriod, 2);
            return 1;
        }
        Integer nextSeq = jdbc.queryForObject(
                "SELECT next_seq FROM recon_run_seq WHERE scenario_code = ? AND accounting_period = ?",
                Integer.class, scenarioCode, accountingPeriod);
        return nextSeq - 1; // 本次占用 = 自增前的值
    }
}
