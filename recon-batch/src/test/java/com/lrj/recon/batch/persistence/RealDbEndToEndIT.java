package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.spi.RecordCursor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A3 · 真库端到端 IT —— 直连<b>外部提供</b>的 MySQL8 / PostgreSQL (系统属性驱动; 未配置则 {@code assumeTrue} 优雅跳过,
 * {@code ./mvnw test} 默认不变红)。是 {@link CollationRealDbIT} (Testcontainers, 仅 collation) 的补充:
 * 当 Docker Engine 与 Testcontainers 内置 docker-java 版本不兼容 (新引擎 {@code /info} 返回 400) 无法自动起容器时,
 * 用本 IT 对 {@code docker run} 起的真库直连验证; 且额外覆盖 CollationRealDbIT 未测的两项 (设计 §5 / 隐患②)。
 *
 * <p>验证矩阵 (每方言一条):
 * <ul>
 *   <li><b>方言 batch 元数据 (V2)</b>: 用<b>生产同款</b> Flyway locations 迁移 V1 领域 + V2 batch + V3 collation ——
 *       MySQL 走官方<b>表式序列</b> {@code BATCH_JOB_SEQ} (不支持 {@code CREATE SEQUENCE}), PG 走 {@code CREATE SEQUENCE};
 *       断言方言序列产物真库落地成功 (H2 测试查不出方言分歧)。</li>
 *   <li><b>V3 collation ALTER 实际效果</b>: {@code ORDER BY (match_key IS NULL), match_key} 返回二进制/码点序 == Java
 *       {@code String.compareTo}; MySQL utf8mb4_bin 是 PAD SPACE ('K1'=='K1 '), PG no-pad ('K1'!='K1 ')。</li>
 *   <li><b>per-bucket 访问命中 {@code idx_merge}</b>: 等值前缀查询 EXPLAIN。</li>
 *   <li><b>真流式游标 (隐患②)</b>: 经生产 {@link JdbcReconRecordStore#cursor} 逐条取 —— MySQL 触发
 *       {@code fetchSize=Integer.MIN_VALUE} 真流式 (forward-only + read-only), 返回顺序 == Java 归并序。</li>
 * </ul>
 *
 * <p>运行示例 (对 {@code docker run} 起的库):
 * <pre>{@code
 * ./mvnw -pl recon-batch -am test -Dtest=RealDbEndToEndIT -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Drecon.it.mysql.url=jdbc:mysql://127.0.0.1:23306/recon \
 *   -Drecon.it.mysql.user=root -Drecon.it.mysql.password=root \
 *   -Drecon.it.postgres.url=jdbc:postgresql://127.0.0.1:26543/recon \
 *   -Drecon.it.postgres.user=recon -Drecon.it.postgres.password=recon
 * }</pre>
 */
class RealDbEndToEndIT {

    private static final String SEG = "SEG1_MKT_ACCT";

    @Test
    void mysql8FullMigrationCollationBatchMetaAndStreaming() {
        String url = System.getProperty("recon.it.mysql.url", "");
        assumeTrue(!url.isBlank(), "recon.it.mysql.url 未配置, 跳过 MySQL 真库端到端");
        DataSource ds = dataSource(url, "recon.it.mysql");
        JdbcTemplate jdbc = migrateProd(ds, "mysql");
        jdbc.update("DELETE FROM recon_record"); // 外部库可能被复用, 清空 staging 保证可重复

        // 1) 方言 batch 元数据: MySQL8 用表式序列 (不支持 CREATE SEQUENCE) —— 断言 BATCH_JOB_SEQ 是真"表"
        assertThat(mysqlBaseTableExists(jdbc, "BATCH_JOB_SEQ"))
                .as("MySQL8 官方表式序列 BATCH_JOB_SEQ 真库落地").isTrue();

        // 2) collation 排序序 == Java (混大小写, 二进制序: 大写码点 < 小写)
        assertThat(perBucketOrder(jdbc, "run-ord", List.of("b1", "A1", "a1", "B1")))
                .containsExactly("A1", "B1", "a1", "b1")
                .isEqualTo(javaSorted("b1", "A1", "a1", "B1"));

        // 3) PAD SPACE: utf8mb4_bin 下 'K1'=='K1 ' (与 PG/Java 发散 —— 靠标准化 trim 归一, 非本迁移)
        insert(jdbc, "run-pad", 0, "K1");
        insert(jdbc, "run-pad", 0, "K1 ");
        assertThat(countByKey(jdbc, "run-pad", "K1"))
                .as("MySQL utf8mb4_bin PAD SPACE").isEqualTo(2L);

        // 4) per-bucket 等值前缀查询命中 idx_merge (MySQL 用 ANALYZE TABLE 语法)
        assertThat(perBucketPlan(jdbc, "run-idx", "ANALYZE TABLE recon_record"))
                .containsIgnoringCase("idx_merge");

        // 5) 真流式游标 (fetchSize=Integer.MIN_VALUE on MySQL)
        assertStreamingCursorOrder(ds);
    }

    @Test
    void postgres16FullMigrationCollationBatchMetaAndStreaming() {
        String url = System.getProperty("recon.it.postgres.url", "");
        assumeTrue(!url.isBlank(), "recon.it.postgres.url 未配置, 跳过 PostgreSQL 真库端到端");
        DataSource ds = dataSource(url, "recon.it.postgres");
        JdbcTemplate jdbc = migrateProd(ds, "postgresql");
        jdbc.update("DELETE FROM recon_record"); // 外部库可能被复用, 清空 staging 保证可重复

        // 1) 方言 batch 元数据: PG 支持 CREATE SEQUENCE —— 断言 BATCH_JOB_SEQ 是真"序列"
        assertThat(pgSequenceExists(jdbc, "BATCH_JOB_SEQ"))
                .as("PG CREATE SEQUENCE BATCH_JOB_SEQ 真库落地").isTrue();

        // 2) collation 排序序 (COLLATE "C" 码点序) == Java
        assertThat(perBucketOrder(jdbc, "run-ord", List.of("b1", "A1", "a1", "B1")))
                .containsExactly("A1", "B1", "a1", "b1")
                .isEqualTo(javaSorted("b1", "A1", "a1", "B1"));

        // 3) no-pad: PG 视 'K1' != 'K1 ' (跨库分歧的另一半, trim 后三库口径一致)
        insert(jdbc, "run-pad", 0, "K1");
        insert(jdbc, "run-pad", 0, "K1 ");
        assertThat(countByKey(jdbc, "run-pad", "K1"))
                .as("PG no-pad").isEqualTo(1L);

        // 4) per-bucket 等值前缀查询命中 idx_merge (PG 用 ANALYZE <table> 语法)
        assertThat(perBucketPlan(jdbc, "run-idx", "ANALYZE recon_record"))
                .containsIgnoringCase("idx_merge");

        // 5) 生产游标路径 (PG 用正 fetchSize) —— 返回序仍 == Java
        assertStreamingCursorOrder(ds);
    }

    // ---------- 助手 ----------

    private static DataSource dataSource(String url, String prefix) {
        return new DriverManagerDataSource(url,
                System.getProperty(prefix + ".user", ""),
                System.getProperty(prefix + ".password", ""));
    }

    /** 生产同款 Flyway locations: V1 领域 + V2 方言 batch 元数据 + V3 方言 collation (与 application.yml 一致)。 */
    private static JdbcTemplate migrateProd(DataSource ds, String vendor) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration",
                        "classpath:db/batch/" + vendor,
                        "classpath:db/schema/" + vendor)
                .load()
                .migrate();
        return new JdbcTemplate(ds);
    }

    /** 单桶 (bucket 0) 插入若干键后, 走 per-bucket 游标同形 SQL 取返回顺序。 */
    private static List<String> perBucketOrder(JdbcTemplate jdbc, String run, List<String> keys) {
        for (String k : keys) {
            insert(jdbc, run, 0, k);
        }
        return jdbc.queryForList("""
                SELECT match_key FROM recon_record
                 WHERE run_id = ? AND segment_id = ? AND side = 'LEFT' AND bucket = 0
                 ORDER BY (match_key IS NULL), match_key
                """, String.class, run, SEG);
    }

    private static List<String> javaSorted(String... keys) {
        List<String> sorted = new ArrayList<>(List.of(keys));
        sorted.sort(String::compareTo);
        return sorted;
    }

    /**
     * 造多桶数据 (bucket 0 仅少量, 提升选择性) + ANALYZE + EXPLAIN per-bucket 查询, 返回计划文本。
     * {@code analyzeSql} 方言相关: MySQL 用 {@code ANALYZE TABLE <t>}, PG 用 {@code ANALYZE <t>} (语法不同)。
     */
    private static String perBucketPlan(JdbcTemplate jdbc, String run, String analyzeSql) {
        for (int i = 0; i < 400; i++) {
            insert(jdbc, run, i % 40, String.format("K%05d", i)); // spread 40 桶
        }
        jdbc.execute(analyzeSql); // 让优化器有统计
        StringBuilder plan = new StringBuilder();
        RowCallbackHandler collector = rs -> {
            int cols = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= cols; i++) {
                plan.append(rs.getString(i)).append(' ');
            }
            plan.append('\n');
        };
        jdbc.query("""
                EXPLAIN SELECT record_id FROM recon_record
                 WHERE run_id = ? AND segment_id = ? AND side = 'LEFT' AND bucket = 0
                 ORDER BY (match_key IS NULL), match_key
                """, collector, run, SEG);
        return plan.toString();
    }

    /** 经生产 {@link JdbcReconRecordStore#cursor} 逐条取, 断言返回序 == Java 码点序 (真流式路径全走一遍)。 */
    private static void assertStreamingCursorOrder(DataSource ds) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        JdbcReconRecordStore store = new JdbcReconRecordStore(jdbc, 1000);
        String run = "run-stream";
        for (String k : List.of("A1", "B1", "C1", "a1", "b1")) {
            insert(jdbc, run, 0, k);
        }
        List<String> drained = new ArrayList<>();
        try (RecordCursor cur = store.cursor(run, SEG, Side.LEFT, 0)) {
            ReconRecord r;
            while ((r = cur.next()) != null) {
                drained.add(r.matchKey() == null ? null : r.matchKey().value());
            }
        }
        assertThat(drained)
                .as("生产游标返回序 (码点序, 大写在前) == Java 归并序")
                .containsExactly("A1", "B1", "C1", "a1", "b1");
    }

    private static Long countByKey(JdbcTemplate jdbc, String run, String key) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_record WHERE run_id = ? AND match_key = ?", Long.class, run, key);
    }

    private static boolean mysqlBaseTableExists(JdbcTemplate jdbc, String table) {
        Long c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                   AND UPPER(table_name) = ?
                """, Long.class, table.toUpperCase());
        return c != null && c > 0;
    }

    private static boolean pgSequenceExists(JdbcTemplate jdbc, String sequence) {
        Long c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.sequences
                 WHERE UPPER(sequence_name) = ?
                """, Long.class, sequence.toUpperCase());
        return c != null && c > 0;
    }

    private static void insert(JdbcTemplate jdbc, String run, int bucket, String matchKey) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO recon_record(record_id, run_id, segment_id, side, source_role, match_key, group_key,
                    bucket, currency, signed_amount_minor, entry_type, biz_time, raw_ref, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                java.util.UUID.randomUUID().toString(), run, SEG, "LEFT", "MARKETING",
                matchKey, matchKey == null ? "G" : matchKey.stripTrailing(), bucket, "USD", 100L,
                "ISSUE", now, "t:" + matchKey, now);
    }
}
