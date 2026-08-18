package com.lrj.recon.source.csv;

import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.SourceAdapter;
import com.lrj.recon.core.spi.SourceDescriptor;
import com.lrj.recon.core.spi.SourceReadContext;

import java.util.Objects;

/**
 * CSV 文件数据源适配器。每次 {@link #open(SourceReadContext)} 打开一条惰性、前向 only 的文件游标，
 * 不把文件整体载入内存；BOM、字符集、列映射、行号血缘和 reject 由游标负责。
 */
public final class CsvSourceAdapter implements SourceAdapter {

    public static final String SOURCE_TYPE = "csv-file";

    @Override
    public String sourceId() {
        return SOURCE_TYPE;
    }

    @Override
    public boolean supports(SourceDescriptor descriptor) {
        return descriptor != null && SOURCE_TYPE.equals(descriptor.sourceType());
    }

    @Override
    public RecordCursor open(SourceReadContext context) {
        Objects.requireNonNull(context, "context");
        if (!supports(context.descriptor())) {
            throw new IllegalArgumentException("CsvSourceAdapter does not support descriptor: "
                    + context.descriptor());
        }
        return CsvRecordCursor.open(context, CsvSourceConfig.from(context.descriptor().params()));
    }
}
