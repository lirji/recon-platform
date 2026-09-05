package com.lrj.recon.core.spi;

import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;

import java.time.Instant;

/**
 * 一次拉取的上下文: 归属 Run / 段 / 侧 / 角色 + 桶数, 供适配器计算 bucket 与 rawRef 血缘。
 */
public record SourceReadContext(
        String runId,
        String tenantId,
        String segmentId,
        Side side,
        SourceRole sourceRole,
        int bucketCount,
        Instant windowFrom,
        Instant windowTo,
        SourceDescriptor descriptor) {

    /** 兼容无租户列的历史源；权益 ODS 描述符一律使用完整构造器。 */
    public SourceReadContext(String runId, String segmentId, Side side, SourceRole sourceRole,
                             int bucketCount, SourceDescriptor descriptor) {
        this(runId, "legacy", segmentId, side, sourceRole, bucketCount,
                Instant.EPOCH, Instant.parse("9999-12-31T23:59:59Z"), descriptor);
    }
}
