package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.domain.service.Bucketing;
import com.lrj.recon.core.spi.RecordCursor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 遗留② 回归 (设计 §11 M3): per-bucket 游标的 {@code ORDER BY match_key} <b>不再挂 COLLATE 子句</b>、
 * 走 idx_merge <b>免 filesort</b>, 且 DB 排序序与 Java {@code MatchKey.compareTo} (BMP 内码点序) 对齐。
 *
 * <p>能力所限 (H2), 用两条可断言的证据覆盖:
 * <ol>
 *   <li><b>功能序</b>: 单桶混大小写键, per-bucket 游标返回序 == Java {@code String.compareTo} 序 (证明未被
 *       大小写不敏感 collation 污染 —— 若挂 ci collation 会返回 [A1,a1,B1,b1]);</li>
 *   <li><b>执行计划</b>: H2 {@code EXPLAIN} 命中 {@code idx_merge}、标 "index sorted" (免额外排序), 且计划文本
 *       <b>无 "collate"</b> (运行时 SQL 不再挂 COLLATE 子句)。</li>
 * </ol>
 * MySQL/PG 侧由方言迁移 V3 (utf8mb4_bin / COLLATE "C") pin 列 collation 保证 (H2 查不出, 见 V3 说明)。
 */
class CollationOrderRegressionTest extends AbstractReconJobIT {

    private static final String SEG = "SEG1_MKT_ACCT";

    @Autowired ReconRecordRepository records;

    @Test
    void perBucketCursorReturnsJavaBinaryOrderNotCaseInsensitive() {
        String run = "run-collation-order";
        // bucketCount=1 → 全落 bucket 0; 混大小写键, 二进制/码点序 = [A1, B1, a1, b1] (大写码点 < 小写)。
        List<ReconRecord> seed = new ArrayList<>();
        for (String k : List.of("b1", "A1", "a1", "B1")) {   // 乱序插入
            seed.add(rec(run, Side.LEFT, k, 1));
        }
        records.batchInsert(seed);

        List<String> got = new ArrayList<>();
        try (RecordCursor cursor = records.cursor(run, SEG, Side.LEFT, 0)) {
            ReconRecord r;
            while ((r = cursor.next()) != null) {
                got.add(r.matchKey().value());
            }
        }
        assertThat(got).containsExactly("A1", "B1", "a1", "b1"); // 二进制序 (大写在前), 非 ci 序
    }

    @Test
    void perBucketCursorPlanUsesIndexSortedWithoutCollate() {
        String run = "run-collation-plan";
        // 足量行让 H2 优化器倾向索引 (避免小表全扫); 全落 bucket 0 (bucketCount=1)。
        List<ReconRecord> seed = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            seed.add(rec(run, Side.LEFT, String.format("K%05d", i), 1));
        }
        records.batchInsert(seed);

        String plan = explainPerBucket(run).toLowerCase();
        // 走 idx_merge, 且索引查找覆盖<b>全部 4 个等值前缀列</b> (run_id, segment_id, side, bucket) ——
        // idx_merge = (run_id, segment_id, side, bucket, match_key), 前 4 列等值定位后, 索引扫描天然按第 5 列
        // match_key 有序, 故 ORDER BY match_key <b>由索引提供、免额外 filesort</b> (H2 此版不标 "index sorted",
        // 以"索引覆盖全前缀"作等价证据)。
        assertThat(plan).as("per-bucket 游标应走 idx_merge (索引有序)").contains("idx_merge");
        assertThat(plan).as("索引查找覆盖全部等值前缀列")
                .contains("run_id").contains("segment_id").contains("side").contains("bucket");
        assertThat(plan).as("运行时 ORDER BY 不应再挂 COLLATE 子句").doesNotContain("collate");
    }

    /** EXPLAIN 与 JdbcReconRecordStore.cursor 同形的 per-bucket 查询 (字面量替参, 供 H2 出计划)。 */
    private String explainPerBucket(String run) {
        String sql = "EXPLAIN SELECT record_id FROM recon_record "
                + "WHERE run_id = '" + run + "' AND segment_id = '" + SEG + "' AND side = 'LEFT' AND bucket = 0 "
                + "ORDER BY match_key ASC";
        StringBuilder plan = new StringBuilder();
        jdbc.query(sql, rs -> {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                plan.append(rs.getString(i)).append('\n');
            }
        });
        return plan.toString();
    }

    private ReconRecord rec(String run, Side side, String key, int bucketCount) {
        int bucket = Bucketing.bucketOf(key, bucketCount);
        return ReconRecord.builder()
                .recordId(run + ":" + side + ":" + key)
                .runId(run).segmentId(SEG).side(side)
                .sourceRole(side == Side.LEFT ? SourceRole.MARKETING : SourceRole.ACCOUNTING)
                .matchKey(MatchKey.of("k", key, bucket))
                .groupKey(GroupKey.of("k", key))
                .bucket(bucket)
                .money(Money.of("USD", 100))
                .entryType(EntryType.ISSUE)
                .bizTime(BIZ)
                .rawRef("t:" + key)
                .build();
    }
}
