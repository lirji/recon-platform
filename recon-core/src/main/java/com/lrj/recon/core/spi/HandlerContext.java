package com.lrj.recon.core.spi;

/**
 * 处理器执行上下文: 归属 Run 与触发操作者。幂等键 = fingerprint + handlerId (见 {@link DiscrepancyHandler})。
 */
public record HandlerContext(String runId, String operator) {
}
