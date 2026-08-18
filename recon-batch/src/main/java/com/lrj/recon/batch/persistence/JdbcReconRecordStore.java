package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.RejectedRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@link ReconRecordRepository} 的 JDBC 实现: staging 表 {@code recon_record} 批插 + 惰性 sort-merge 游标 + 分批清理。
 */
@Repository
public class JdbcReconRecordStore implements ReconRecordRepository {

    private static final String SELECT_COLS = """
            record_id, run_id, segment_id, side, source_role, match_key, group_key, bucket,
            currency, signed_amount_minor, base_amount_minor, fx_rate, fx_rate_time, fx_rate_source,
            entry_type, biz_status, biz_time, posting_time, claimed_run_id, raw_ref, created_at
            """;

    private final JdbcTemplate jdbc;
    /** 非 MySQL (H2/PG) 的前向游标批量取行数; MySQL 走 {@link Integer#MIN_VALUE} 真流式 (隐患②)。 */
    private final int fetchSize;

    public JdbcReconRecordStore(JdbcTemplate jdbc,
                                @Value("${recon.record-cursor.fetch-size:1000}") int fetchSize) {
        this.jdbc = jdbc;
        this.fetchSize = fetchSize;
    }

    private static final RowMapper<ReconRecord> MAPPER = (rs, n) -> {
        String matchKeyValue = rs.getString("match_key");
        int bucket = rs.getInt("bucket");
        MatchKey matchKey = matchKeyValue == null ? null : MatchKey.of("match_key", matchKeyValue, bucket);
        return ReconRecord.builder()
                .recordId(rs.getString("record_id"))
                .runId(rs.getString("run_id"))
                .segmentId(rs.getString("segment_id"))
                .side(Side.valueOf(rs.getString("side")))
                .sourceRole(SourceRole.valueOf(rs.getString("source_role")))
                .matchKey(matchKey)
                .groupKey(GroupKey.of("group_key", rs.getString("group_key")))
                .bucket(bucket)
                .money(Money.of(rs.getString("currency"), rs.getLong("signed_amount_minor")))
                .baseAmountMinor(SqlTimes.longOrNull(rs, "base_amount_minor"))
                .fxRate(rs.getBigDecimal("fx_rate"))
                .fxRateTime(SqlTimes.instant(rs, "fx_rate_time"))
                .fxRateSource(rs.getString("fx_rate_source"))
                .entryType(EntryType.valueOf(rs.getString("entry_type")))
                .bizStatus(rs.getString("biz_status"))
                .bizTime(SqlTimes.instant(rs, "biz_time"))
                .postingTime(SqlTimes.instant(rs, "posting_time"))
                .claimedRunId(rs.getString("claimed_run_id"))
                .rawRef(rs.getString("raw_ref"))
                .build();
    };

    @Override
    public void batchInsert(Iterable<ReconRecord> records) {
        List<ReconRecord> batch = new ArrayList<>();
        for (ReconRecord r : records) {
            batch.add(r);
        }
        if (batch.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        jdbc.batchUpdate("""
                INSERT INTO recon_record(record_id, run_id, segment_id, side, source_role, match_key,
                    group_key, bucket, currency, signed_amount_minor, base_amount_minor, fx_rate,
                    fx_rate_time, fx_rate_source, entry_type, biz_status, biz_time, posting_time,
                    claimed_run_id, raw_ref, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ReconRecord r = batch.get(i);
                ps.setString(1, r.recordId());
                ps.setString(2, r.runId());
                ps.setString(3, r.segmentId());
                ps.setString(4, r.side().name());
                ps.setString(5, r.sourceRole().name());
                ps.setString(6, r.matchKey() == null ? null : r.matchKey().value());
                ps.setString(7, r.groupKey().value());
                ps.setInt(8, r.bucket());
                ps.setString(9, r.currency());
                ps.setLong(10, r.signedAmountMinor());
                ps.setObject(11, r.baseAmountMinor());
                ps.setBigDecimal(12, r.fxRate());
                ps.setTimestamp(13, SqlTimes.ts(r.fxRateTime()));
                ps.setString(14, r.fxRateSource());
                ps.setString(15, r.entryType().name());
                ps.setString(16, r.bizStatus());
                ps.setTimestamp(17, SqlTimes.ts(r.bizTime()));
                ps.setTimestamp(18, SqlTimes.ts(r.postingTime()));
                ps.setString(19, r.claimedRunId());
                ps.setString(20, r.rawRef());
                ps.setTimestamp(21, SqlTimes.ts(now));
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    @Override
    public RecordCursor cursor(String runId, String segmentId, Side side, int bucket) {
        // M3 分桶并行的每 partition 游标 (单桶内 match_key 升序)。
        //
        // #8 修复 (M4 地雷 + 可移植 NULL 排序): 原注释谎称"单桶 refine 不变式保证无 null 键", 且用纯
        // `ORDER BY match_key` —— 但 M4 SpineBridgeKeyExtractor 下 spine 缺记录侧会产出 null match_key,
        // 而纯 ORDER BY 的 NULL 位置方言相关 (MySQL NULLS-FIRST, PG NULLS-LAST): 若 null 排在头部, 会插到
        // 非空键簇之前, 阻断 SegmentGroupCursor 的 sort-merge (把有对手的键误判成单边组 → 假 MISSING/EXTRA)。
        // 故改为与 cursorBySegmentSide 一致的可移植 `ORDER BY (match_key IS NULL), match_key`: 非空键升序在前、
        // null 键统一排最后, SegmentGroupCursor 的 null 相位据此把 null 键逐条路由为单边组、绝不喂给
        // SortMergeJoiner (joiner 拒 null 键会抛异常)。
        //
        // 取舍: `(match_key IS NULL)` 前缀是表达式, 部分库 (索引原生 NULL 位置与所需相反时) 会为排序补一次 sort;
        // 但 idx_merge (run_id, segment_id, side, bucket, match_key) 仍供等值前缀 ref 访问。M2/M3 无 null 键
        // (IdentityKeyExtractor), (match_key IS NULL) 恒为 0 → 退化为纯 match_key 序; 正确性优先于免 filesort。
        // match_key 列 collation 已在方言迁移 V3 pin (MySQL utf8mb4_bin / PG COLLATE "C" / H2 码点序), 与 Java
        // MatchKey.compareTo 对齐, 免运行时 COLLATE 子句。
        String sql = """
                SELECT %s FROM recon_record
                 WHERE run_id = ? AND segment_id = ? AND side = ? AND bucket = ?
                 ORDER BY (match_key IS NULL), match_key ASC
                """.formatted(SELECT_COLS.trim());
        return streamingCursor(sql, runId, segmentId, side.name(), bucket);
    }

    @Override
    public RecordCursor cursorBySegmentSide(String runId, String segmentId, Side side) {
        // M2 单线程全桶游标: 可移植 NULL 排序 —— 非空 match_key 升序在前, null 键统一排最后 (隐患①),
        // 消除 MySQL(NULLS FIRST) 与 PG(NULLS LAST) 方言差异, 保证 SortMergeJoiner 只吃到连续的非空键。
        // (全桶跨 bucket 混排, 本就非 idx_merge 有序前缀, 排序落盘可接受; per-bucket 游标才是免 filesort 的热路径。)
        String sql = """
                SELECT %s FROM recon_record
                 WHERE run_id = ? AND segment_id = ? AND side = ?
                 ORDER BY (match_key IS NULL), match_key ASC
                """.formatted(SELECT_COLS.trim());
        return streamingCursor(sql, runId, segmentId, side.name());
    }

    @Override
    public Map<Integer, Long> bucketRowCounts(String runId, String segmentId) {
        // 一次分组聚合 (走 idx_merge 前缀 run_id, segment_id), 非全量拉行; 供 M3 倾斜检测 (SkewDetector)。
        Map<Integer, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT bucket, COUNT(*) AS c FROM recon_record
                 WHERE run_id = ? AND segment_id = ?
                 GROUP BY bucket ORDER BY bucket
                """, rs -> {
            counts.put(rs.getInt("bucket"), rs.getLong("c"));
        }, runId, segmentId);
        return counts;
    }

    /**
     * 惰性前向游标: forward-only + read-only + 方言相关 fetchSize (MySQL 真流式 {@link Integer#MIN_VALUE},
     * 隐患②; H2/PG 用正 fetchSize)。cursor-backed Stream, 常量内存, {@link RecordCursor#close()} 释放连接。
     */
    private RecordCursor streamingCursor(String sql, Object... args) {
        PreparedStatementCreator psc = con -> {
            PreparedStatement ps = con.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setFetchSize(resolveFetchSize(con));
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        };
        Stream<ReconRecord> stream = jdbc.queryForStream(psc, MAPPER);
        return new StreamRecordCursor(stream);
    }

    /** MySQL 真流式需 fetchSize=Integer.MIN_VALUE + forward-only; 其它库 (H2 测试/PG) 用配置的正 fetchSize。 */
    private int resolveFetchSize(Connection con) throws SQLException {
        String product = con.getMetaData().getDatabaseProductName();
        if (product != null && product.toLowerCase().contains("mysql")) {
            return Integer.MIN_VALUE;
        }
        return fetchSize;
    }

    @Override
    public int deleteByRunBounded(String runId, int limit) {
        return jdbc.update("""
                DELETE FROM recon_record
                 WHERE record_id IN (
                     SELECT sub.record_id FROM (
                         SELECT record_id FROM recon_record WHERE run_id = ? LIMIT ?
                     ) sub
                 )
                """, runId, limit);
    }

    /** 惰性游标: 包 queryForStream 的 cursor-backed Stream, next() 逐条拉, close() 释放连接。 */
    private static final class StreamRecordCursor implements RecordCursor {
        private final Stream<ReconRecord> stream;
        private final Iterator<ReconRecord> it;

        StreamRecordCursor(Stream<ReconRecord> stream) {
            this.stream = stream;
            this.it = stream.iterator();
        }

        @Override
        public ReconRecord next() {
            return it.hasNext() ? it.next() : null;
        }

        @Override
        public List<RejectedRow> rejects() {
            return List.of();
        }

        @Override
        public void close() {
            stream.close();
        }
    }
}
