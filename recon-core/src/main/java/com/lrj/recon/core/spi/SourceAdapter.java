package com.lrj.recon.core.spi;

/**
 * 插件 1) 数据源: 拉取 + 标准化为 {@link com.lrj.recon.core.domain.model.ReconRecord}。
 *
 * <p>MVP (M0) 只定义接口; db/csv 实现归外圈模块 (M1+): 惰性前向游标, <b>禁全量 load</b>。
 */
public interface SourceAdapter {

    /** 适配器标识, 如 "db" | "csv-file"。 */
    String sourceId();

    /** 是否支持给定描述符。 */
    boolean supports(SourceDescriptor descriptor);

    /** 打开惰性前向游标。 */
    RecordCursor open(SourceReadContext context);
}
