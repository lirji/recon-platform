package com.lrj.recon.core.spi;

/**
 * 畸形行: 标准化失败但不中断整流的记录, 带血缘与原因, 落 recon_record_reject (M1+)。
 */
public record RejectedRow(String rawRef, String reason, String rawPayload) {
}
