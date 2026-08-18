package com.lrj.recon.source.db;

import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.SourceDescriptor;
import com.lrj.recon.core.spi.SourceReadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DbSourceAdapter keyset 游标: 分页遍历不漏不重, 覆盖 空表 / 单页 / 多页 / 恰好整页 边界; 畸形行入 rejects。
 * 用 H2 内存库 (MySQL 兼容模式), 免 Docker。
 */
class DbSourceAdapterTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DbSourceAdapter adapter;

    @BeforeEach
    void setUp() {
        String db = "srcdb" + DB_SEQ.incrementAndGet();
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE mkt_issue (
                  id BIGINT PRIMARY KEY,
                  issue_id VARCHAR(64),
                  amount_minor BIGINT,
                  ccy CHAR(3),
                  entry_type VARCHAR(16)
                )
                """);
        adapter = new DbSourceAdapter(jdbc);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    private SourceReadContext context(int pageSize) {
        Map<String, String> params = new HashMap<>();
        params.put(DbSourceConfig.P_TABLE, "mkt_issue");
        params.put(DbSourceConfig.P_ID_COLUMN, "id");
        params.put(DbSourceConfig.P_MATCH_KEY_COLUMN, "issue_id");
        params.put(DbSourceConfig.P_MATCH_KEY_FIELD, "marketingIssueId");
        params.put(DbSourceConfig.P_GROUP_KEY_COLUMN, "issue_id");
        params.put(DbSourceConfig.P_CURRENCY_COLUMN, "ccy");
        params.put(DbSourceConfig.P_AMOUNT_COLUMN, "amount_minor");
        params.put(DbSourceConfig.P_ENTRY_TYPE_COLUMN, "entry_type");
        params.put(DbSourceConfig.P_PAGE_SIZE, String.valueOf(pageSize));
        SourceDescriptor descriptor = new SourceDescriptor(DbSourceAdapter.SOURCE_TYPE, params);
        return new SourceReadContext("run-1", "SEG1_MKT_ACCT", Side.LEFT, SourceRole.MARKETING, 64, descriptor);
    }

    private void insert(long id, String issueId, long amount, String ccy) {
        jdbc.update("INSERT INTO mkt_issue(id, issue_id, amount_minor, ccy, entry_type) VALUES (?,?,?,?, 'ISSUE')",
                id, issueId, amount, ccy);
    }

    private List<ReconRecord> drain(int pageSize) {
        List<ReconRecord> out = new ArrayList<>();
        try (RecordCursor cursor = adapter.open(context(pageSize))) {
            ReconRecord r;
            while ((r = cursor.next()) != null) {
                out.add(r);
            }
        }
        return out;
    }

    @Test
    void emptyTableYieldsNothing() {
        assertThat(drain(2)).isEmpty();
    }

    @Test
    void singlePage() {
        insert(1, "I-1", 1000, "USD");
        insert(2, "I-2", 2000, "USD");
        List<ReconRecord> records = drain(10); // pageSize > rows
        assertThat(records).extracting(ReconRecord::signedAmountMinor).containsExactly(1000L, 2000L);
        assertThat(records).extracting(r -> r.matchKey().value()).containsExactly("I-1", "I-2");
        assertThat(records).extracting(ReconRecord::rawRef).containsExactly("mkt_issue:1", "mkt_issue:2");
        assertThat(records.get(0).runId()).isEqualTo("run-1");
        assertThat(records.get(0).side()).isEqualTo(Side.LEFT);
    }

    @Test
    void multiPageNoGapNoDuplicate() {
        int total = 25;
        for (int i = 1; i <= total; i++) {
            insert(i, "I-" + i, i * 100L, "USD");
        }
        List<ReconRecord> records = drain(4); // 4 每页 -> 7 页 (最后一页 1 行)
        assertThat(records).hasSize(total);
        // 严格升序、无重复、无遗漏 (按 id -> rawRef 顺序)
        List<Long> ids = records.stream().map(r -> Long.parseLong(r.rawRef().substring("mkt_issue:".length()))).toList();
        assertThat(ids).isSorted();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, total).boxed().toList());
    }

    @Test
    void exactlyOnePageBoundary() {
        for (int i = 1; i <= 4; i++) {
            insert(i, "I-" + i, i, "USD");
        }
        // rows == pageSize: 首页满, 次页 0 行 -> 正常收尾, 不漏不重不死循环
        assertThat(drain(4)).hasSize(4);
    }

    @Test
    void malformedRowsGoToRejectsNotRecords() {
        insert(1, "I-1", 1000, "USD");
        insert(2, null, 2000, "USD");     // null group_key -> reject
        insert(3, "I-3", 3000, null);     // null currency -> reject
        insert(4, "I-4", 4000, "USD");
        List<ReconRecord> out = new ArrayList<>();
        List<?> rejects;
        try (RecordCursor cursor = adapter.open(context(2))) {
            ReconRecord r;
            while ((r = cursor.next()) != null) {
                out.add(r);
            }
            rejects = cursor.rejects();
        }
        assertThat(out).extracting(r -> r.matchKey().value()).containsExactly("I-1", "I-4");
        assertThat(rejects).hasSize(2);
    }
}
