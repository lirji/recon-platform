package com.lrj.recon.core.spi;

import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;

/**
 * 一次拉取的上下文: 归属 Run / 段 / 侧 / 角色 + 桶数, 供适配器计算 bucket 与 rawRef 血缘。
 */
public record SourceReadContext(
        String runId,
        String segmentId,
        Side side,
        SourceRole sourceRole,
        int bucketCount,
        SourceDescriptor descriptor) {
}
