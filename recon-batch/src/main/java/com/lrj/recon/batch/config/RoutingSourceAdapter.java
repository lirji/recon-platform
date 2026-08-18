package com.lrj.recon.batch.config;

import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.SourceAdapter;
import com.lrj.recon.core.spi.SourceDescriptor;
import com.lrj.recon.core.spi.SourceReadContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 组合根数据源路由：按 {@link SourceDescriptor#sourceType()} 选择唯一外圈适配器。
 * 构造期拒绝重复 sourceId，运行期拒绝未知类型，避免静默选错数据源。
 */
public final class RoutingSourceAdapter implements SourceAdapter {

    public static final String SOURCE_ID = "routing";

    private final Map<String, SourceAdapter> delegates;

    public RoutingSourceAdapter(List<? extends SourceAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        Map<String, SourceAdapter> indexed = new LinkedHashMap<>();
        for (SourceAdapter adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter");
            SourceAdapter previous = indexed.putIfAbsent(adapter.sourceId(), adapter);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate SourceAdapter sourceId: " + adapter.sourceId());
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("at least one SourceAdapter is required");
        }
        this.delegates = Map.copyOf(indexed);
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public boolean supports(SourceDescriptor descriptor) {
        return descriptor != null && delegates.containsKey(descriptor.sourceType());
    }

    @Override
    public RecordCursor open(SourceReadContext context) {
        Objects.requireNonNull(context, "context");
        SourceDescriptor descriptor = context.descriptor();
        SourceAdapter delegate = descriptor == null ? null : delegates.get(descriptor.sourceType());
        if (delegate == null || !delegate.supports(descriptor)) {
            throw new IllegalArgumentException("no SourceAdapter registered for descriptor: " + descriptor
                    + "; available=" + delegates.keySet());
        }
        return delegate.open(context);
    }
}
