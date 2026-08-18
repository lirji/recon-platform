package com.lrj.recon.batch.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在 JDBC savepoint 中执行“唯一键幂等插入”。
 *
 * <p>PostgreSQL 的唯一键异常会把当前事务标成 aborted；仅在 Java 中捕获异常后继续写库并不安全。
 * {@link TransactionDefinition#PROPAGATION_NESTED NESTED} 在已有 chunk/在线事务内使用 savepoint，命中唯一键时
 * 先回滚到 savepoint 再返回 {@code false}，外层事务仍可继续；无外层事务时则退化为一个普通短事务。
 */
@Component
final class JdbcDuplicateSafeInsert {

    private final TransactionTemplate nested;

    JdbcDuplicateSafeInsert(PlatformTransactionManager txManager) {
        this.nested = new TransactionTemplate(txManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** 首次插入返回 {@code true}；任一唯一键已存在返回 {@code false}，且不污染外层事务。 */
    boolean execute(Runnable insert) {
        try {
            nested.executeWithoutResult(status -> insert.run());
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
