package com.lrj.recon.core.domain.model;

/**
 * 乐观锁 / 唯一键冲突时抛出 (领域异常, 纯 Java, 与持久化技术无关)。
 *
 * <p>持久化端口 (application.port.out) 的契约异常:
 * <ul>
 *   <li>{@code claim(run)} 命中 {@code uk_run} 重复 → 抛本异常 (挡并发重复 Run);</li>
 *   <li>{@code save(run, expectedRevision)} 条件更新影响行数 ≠ 1 (revision 已被他人推进) → 抛本异常;</li>
 *   <li>人工处置 {@code upsert} version 乐观锁失败 → 抛本异常。</li>
 * </ul>
 * 由外圈 Jdbc*Store 把底层 {@code DuplicateKeyException} / 条件更新失败翻译成本异常, 领域层只认本异常。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
