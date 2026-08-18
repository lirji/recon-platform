package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.domain.service.KeyNormalizer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #6 遗留② collation <b>真库</b>验证 (H2 no-op 查不出): 用 Testcontainers 起真实 MySQL8 / PostgreSQL, 跑 V1 +
 * 方言 V3 迁移, 断言 match_key 列 collation 的<b>实际效果</b> 与 Java {@code MatchKey.compareTo} 对齐:
 * <ul>
 *   <li><b>排序序</b>: {@code ORDER BY (match_key IS NULL), match_key} 返回二进制/码点序 (大小写敏感), == Java 序;</li>
 *   <li><b>尾随空格 (PAD SPACE)</b>: MySQL utf8mb4_bin 视 'K1'=='K1 ' (PAD SPACE), PG(no-pad) 视为不等 —— 印证 #2
 *       为何要在标准化处 trim (根除跨库分歧); {@link KeyNormalizer} 把两者归一;</li>
 *   <li><b>per-bucket 访问</b>: 等值前缀查询 EXPLAIN 命中 idx_merge。</li>
 * </ul>
 *
 * <p><b>Docker 守卫</b>: {@code DockerClientFactory.instance().isDockerAvailable()} → {@code assumeTrue} 优雅跳过,
 * 无 Docker 时 {@code ./mvnw -q test} 不变红; 有 Docker 时对真库真跑。手工管理容器 (try-with-resources), 不用
 * {@code @Testcontainers} 扩展 —— 避免 Docker 不可用时扩展直接失败而非跳过。
 */
class CollationRealDbIT {

    private static final String SEG = "SEG1_MKT_ACCT";

    @Test
    void mysql8CollationAndPadSpaceBehaviour() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用, 跳过 MySQL 真库校验");
        try (MySQLContainer<?> mysql = new MySQLContainer<>(
                DockerImageName.parse("mysql:8").asCompatibleSubstituteFor("mysql"))) {
            mysql.start();
            JdbcTemplate jdbc = migrate(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                    "classpath:db/schema/mysql");

            // 1) 排序序: 混大小写键, 二进制序 (大写码点 < 小写) == Java String.compareTo
            assertThat(perBucketOrder(jdbc, "run-ord", List.of("b1", "A1", "a1", "B1")))
                    .containsExactly("A1", "B1", "a1", "b1")
                    .isEqualTo(javaSorted("b1", "A1", "a1", "B1"));

            // 2) PAD SPACE: 'K1' 与 'K1 ' 在 utf8mb4_bin 下相等 (WHERE = 命中两行) —— 这正是 #2 要 trim 的坑
            insert(jdbc, "run-pad", 0, "K1");
            insert(jdbc, "run-pad", 0, "K1 ");   // 尾随空格
            Long padMatches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recon_record WHERE run_id='run-pad' AND match_key = 'K1'", Long.class);
            assertThat(padMatches).as("MySQL utf8mb4_bin 是 PAD SPACE, 'K1'=='K1 '").isEqualTo(2L);
            // KeyNormalizer 把两者归一 → 标准化后不会出现该坑
            assertThat(KeyNormalizer.normalizeTrailing("K1 ")).isEqualTo(KeyNormalizer.normalizeTrailing("K1"));

            // 3) per-bucket 等值前缀查询命中 idx_merge
            assertThat(perBucketPlan(jdbc, "run-idx")).containsIgnoringCase("idx_merge");
        }
    }

    @Test
    void postgresqlCollationAndNoPadBehaviour() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用, 跳过 PostgreSQL 真库校验");
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:16").asCompatibleSubstituteFor("postgres"))) {
            pg.start();
            JdbcTemplate jdbc = migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(),
                    "classpath:db/schema/postgresql");

            // 1) 排序序: COLLATE "C" (码点序) == Java
            assertThat(perBucketOrder(jdbc, "run-ord", List.of("b1", "A1", "a1", "B1")))
                    .containsExactly("A1", "B1", "a1", "b1")
                    .isEqualTo(javaSorted("b1", "A1", "a1", "B1"));

            // 2) no-pad: PG 视 'K1' != 'K1 ' (与 MySQL PAD SPACE 相反) —— 跨库分歧的另一半, trim 后三库口径一致
            insert(jdbc, "run-pad", 0, "K1");
            insert(jdbc, "run-pad", 0, "K1 ");
            Long padMatches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recon_record WHERE run_id='run-pad' AND match_key = 'K1'", Long.class);
            assertThat(padMatches).as("PG 是 no-pad, 'K1' != 'K1 '").isEqualTo(1L);

            // 3) per-bucket 等值前缀查询命中 idx_merge (spread 到多桶使 bucket 0 选择性够高, 再 ANALYZE)
            assertThat(perBucketPlan(jdbc, "run-idx")).containsIgnoringCase("idx_merge");
        }
    }

    // ---------- 助手 ----------

    private static JdbcTemplate migrate(String url, String user, String pw, String vendorLocation) {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, pw);
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration", vendorLocation) // V1 领域 schema + 方言 V3 (跳过 V2 Batch 元数据)
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

    /** Java 侧参照序 (MatchKey.compareTo == String.compareTo, 码点序)。 */
    private static List<String> javaSorted(String... keys) {
        List<String> sorted = new ArrayList<>(List.of(keys));
        sorted.sort(String::compareTo);
        return sorted;
    }

    /** 造多桶数据 (bucket 0 仅少量, 提升选择性) + EXPLAIN per-bucket 查询, 返回计划文本。 */
    private static String perBucketPlan(JdbcTemplate jdbc, String run) {
        for (int i = 0; i < 400; i++) {
            insert(jdbc, run, i % 40, String.format("K%05d", i)); // spread 40 桶
        }
        jdbc.execute("ANALYZE recon_record"); // MySQL8/PG 均支持, 让优化器有统计
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
