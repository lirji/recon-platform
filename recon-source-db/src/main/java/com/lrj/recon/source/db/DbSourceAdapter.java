package com.lrj.recon.source.db;

import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.SourceAdapter;
import com.lrj.recon.core.spi.SourceDescriptor;
import com.lrj.recon.core.spi.SourceReadContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Objects;

/**
 * 数据源适配器 (DB): 用 {@link JdbcTemplate} <b>keyset 游标</b>惰性前向分页拉取源表并标准化为
 * {@link com.lrj.recon.core.domain.model.ReconRecord} (设计 §4)。
 *
 * <p>分页语义 (设计 §4/§11): {@code WHERE <id> > ? ORDER BY <id> ASC LIMIT n}, <b>前向 only, 不全量 load</b>,
 * 常量内存 (只驻当前页缓冲)。血缘 {@code rawRef = 表:主键}。
 *
 * <p>{@link SourceDescriptor#params()} 约定 (key 见 {@link DbSourceConfig}): 声明源表名、keyset 主键列
 * 及各标准化列的映射; {@code bucketCount} 从 {@link SourceReadContext} 取, {@code bucket = floorMod(hash(group_key), N)}。
 *
 * <p>MVP 定位: 本适配器只做"拉取 + 基础标准化"。段内桥接/键精化 (SpineBridgeKeyExtractor) 归 M4;
 * 此处按描述符列直接映射 match_key/group_key, 满足 M1 端到端游标可测。
 */
public final class DbSourceAdapter implements SourceAdapter {

    public static final String SOURCE_TYPE = "db";

    private final JdbcTemplate jdbc;

    public DbSourceAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

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
        SourceDescriptor descriptor = context.descriptor();
        if (!supports(descriptor)) {
            throw new IllegalArgumentException("DbSourceAdapter does not support descriptor: " + descriptor);
        }
        Map<String, String> params = descriptor.params();
        DbSourceConfig config = DbSourceConfig.from(params);
        return new KeysetRecordCursor(jdbc, context, config);
    }
}
