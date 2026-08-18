package com.lrj.recon.core.spi;

/**
 * 处理器种类 (修补⑤: 区分事务性 / 外部副作用)。
 * <ul>
 *   <li>{@link #TRANSACTIONAL}: 与判差写库同事务 (台账、冲正建议 insertIfAbsent);</li>
 *   <li>{@link #EXTERNAL_SIDE_EFFECT}: 外部副作用 (告警), 只写 outbox, 批后中继投递, 杜绝 chunk 重试重复触发。</li>
 * </ul>
 */
public enum HandlerKind {
    TRANSACTIONAL,
    EXTERNAL_SIDE_EFFECT
}
