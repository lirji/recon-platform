package com.lrj.recon.source.db;

import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.service.Bucketing;
import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.RejectedRow;
import com.lrj.recon.core.spi.SourceReadContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * keyset 惰性前向游标: 每次 {@code WHERE id > ? ORDER BY id ASC LIMIT pageSize} 拉一页, 常量内存 (只驻一页)。
 *
 * <p>不漏不重: 严格按 id 升序、以上一页末尾 id 为下界继续; 末页 (行数 &lt; pageSize) 后置 exhausted, 再取即 null。
 * 标准化失败的行 (空 group/currency/amount 或币种非法) 入 {@link #rejects()} 且不中断整流。
 */
final class KeysetRecordCursor implements RecordCursor {

    private final JdbcTemplate jdbc;
    private final SourceReadContext ctx;
    private final DbSourceConfig cfg;
    private final RowMapper<Row> mapper;
    private final List<RejectedRow> rejects = new ArrayList<>();

    private List<Row> page = List.of();
    private int index = 0;
    private Object lastId = null;
    private boolean exhausted = false;

    KeysetRecordCursor(JdbcTemplate jdbc, SourceReadContext ctx, DbSourceConfig cfg) {
        this.jdbc = jdbc;
        this.ctx = ctx;
        this.cfg = cfg;
        this.mapper = this::mapRow;
    }

    @Override
    public ReconRecord next() {
        while (true) {
            if (index >= page.size()) {
                if (exhausted) {
                    return null;
                }
                fetchNextPage();
                if (page.isEmpty()) {
                    return null;
                }
            }
            Row row = page.get(index++);
            if (row.reject != null) {
                rejects.add(row.reject);
                continue;
            }
            return row.record;
        }
    }

    private void fetchNextPage() {
        boolean firstPage = (lastId == null);
        String sql = "SELECT * FROM " + cfg.table
                + (firstPage ? "" : " WHERE " + cfg.idColumn + " > ?")
                + " ORDER BY " + cfg.idColumn + " ASC LIMIT ?";
        List<Row> rows = firstPage
                ? jdbc.query(sql, mapper, cfg.pageSize)
                : jdbc.query(sql, mapper, lastId, cfg.pageSize);
        this.page = rows;
        this.index = 0;
        if (rows.size() < cfg.pageSize) {
            this.exhausted = true;
        }
        if (!rows.isEmpty()) {
            this.lastId = rows.get(rows.size() - 1).id;
        }
    }

    private Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        Object id = rs.getObject(cfg.idColumn);
        String rawRef = cfg.table + ":" + id;

        String groupValue = rs.getString(cfg.groupKeyColumn);
        if (groupValue == null) {
            return Row.reject(id, new RejectedRow(rawRef, "null group_key", null));
        }
        String currency = rs.getString(cfg.currencyColumn);
        if (currency == null) {
            return Row.reject(id, new RejectedRow(rawRef, "null currency", null));
        }
        Object rawAmount = rs.getObject(cfg.amountColumn);
        if (rawAmount == null) {
            return Row.reject(id, new RejectedRow(rawRef, "null amount", null));
        }
        long amountMinor = rs.getLong(cfg.amountColumn);

        Money money;
        try {
            money = Money.of(currency, amountMinor);
        } catch (RuntimeException invalid) {
            return Row.reject(id, new RejectedRow(rawRef, "invalid money: " + invalid.getMessage(), null));
        }

        int bucket = bucketOf(groupValue);
        MatchKey matchKey = null;
        if (cfg.matchKeyColumn != null) {
            String mkValue = rs.getString(cfg.matchKeyColumn);
            if (mkValue != null) {
                matchKey = MatchKey.of(cfg.matchKeyField, mkValue, bucket);
            }
        }
        EntryType entryType = parseEntryType(rs);

        ReconRecord record = ReconRecord.builder()
                .recordId(rawRef)
                .runId(ctx.runId())
                .segmentId(ctx.segmentId())
                .side(ctx.side())
                .sourceRole(ctx.sourceRole())
                .matchKey(matchKey)
                .groupKey(GroupKey.of(cfg.groupKeyField, groupValue))
                .bucket(bucket)
                .money(money)
                .entryType(entryType)
                .bizStatus(cfg.bizStatusColumn == null ? null : rs.getString(cfg.bizStatusColumn))
                .bizTime(instant(rs, cfg.bizTimeColumn))
                .postingTime(instant(rs, cfg.postingTimeColumn))
                .rawRef(rawRef)
                .build();
        return Row.record(id, record);
    }

    private EntryType parseEntryType(ResultSet rs) throws SQLException {
        if (cfg.entryTypeColumn == null) {
            return EntryType.ISSUE;
        }
        String raw = rs.getString(cfg.entryTypeColumn);
        if (raw == null || raw.isBlank()) {
            return EntryType.ISSUE;
        }
        try {
            return EntryType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return EntryType.ISSUE;
        }
    }

    private int bucketOf(String groupValue) {
        // 委托单一分桶工具 (修补①/ADR-11), 消除内联同式副本的漂移风险; groupValue 已在 mapRow 上游判非空。
        return Bucketing.bucketOf(groupValue, ctx.bucketCount());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        if (column == null) {
            return null;
        }
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    @Override
    public List<RejectedRow> rejects() {
        return List.copyOf(rejects);
    }

    @Override
    public void close() {
        // keyset 游标不持有长连接 (每页独立 query), 无需释放; 清空缓冲便于 GC。
        this.page = List.of();
        this.index = 0;
        this.exhausted = true;
    }

    /** 一行的拉取结果: 成功标准化的 record 或被拒的 reject, 二者恰一 (都带 id 供 keyset 推进)。 */
    private static final class Row {
        final Object id;
        final ReconRecord record;
        final RejectedRow reject;

        private Row(Object id, ReconRecord record, RejectedRow reject) {
            this.id = id;
            this.record = record;
            this.reject = reject;
        }

        static Row record(Object id, ReconRecord record) {
            return new Row(id, record, null);
        }

        static Row reject(Object id, RejectedRow reject) {
            return new Row(id, null, reject);
        }
    }
}
