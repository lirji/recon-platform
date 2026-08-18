package com.lrj.recon.core.spi;

import java.util.Map;

/**
 * 数据源描述符: 声明源类型与定位参数, 供 {@link SourceAdapter#supports(SourceDescriptor)} 选择适配器。
 * (M1+ 由外圈 db/csv 适配器消费; core 只定义形状。)
 */
public record SourceDescriptor(String sourceType, Map<String, String> params) {

    public SourceDescriptor {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
