package com.lrj.recon.core.spi;

import com.lrj.recon.core.domain.model.ReconRecord;

import java.util.List;

/**
 * 惰性前向游标: 逐条产出标准化记录, 常量内存。
 */
public interface RecordCursor extends AutoCloseable {

    /** 下一条记录; 无更多返回 {@code null}。 */
    ReconRecord next();

    @Override
    void close();

    /** 本次读取过程中被拒绝的畸形行 (不中断整流)。默认无。 */
    default List<RejectedRow> rejects() {
        return List.of();
    }
}
