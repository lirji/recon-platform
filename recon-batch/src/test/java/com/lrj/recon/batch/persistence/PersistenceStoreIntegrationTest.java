package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.AlertOutboxRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyDispositionRepository;
import com.lrj.recon.core.application.port.out.DiscrepancyRepository;
import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.application.port.out.ReconReportRepository;
import com.lrj.recon.core.application.port.out.ReconRunRepository;
import com.lrj.recon.core.application.port.out.ReversalSuggestionRepository;
import com.lrj.recon.core.domain.model.AlertOutbox;
import com.lrj.recon.core.domain.model.AlertStatus;
import com.lrj.recon.core.domain.model.ConflictException;
import com.lrj.recon.core.domain.model.Discrepancy;
import com.lrj.recon.core.domain.model.DiscrepancyDisposition;
import com.lrj.recon.core.domain.model.DiscrepancyType;
import com.lrj.recon.core.domain.model.DispositionStatus;
import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.ReconReport;
import com.lrj.recon.core.domain.model.ReconRun;
import com.lrj.recon.core.domain.model.ReconRunStatus;
import com.lrj.recon.core.domain.model.ReversalStatus;
import com.lrj.recon.core.domain.model.ReversalSuggestion;
import com.lrj.recon.core.domain.model.RunKey;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.spi.RecordCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M1 持久化层 H2 集成测试 (MySQL 兼容模式, 免 Docker): 覆盖 Flyway V1/V2 迁移 + 7 个 Jdbc*Store 的
 * 幂等 / 乐观锁 / 分批删除 / 游标排序 语义。共享一个 Spring 上下文, {@link #cleanup()} 保证方法间隔离。
 */
@SpringBootTest
class PersistenceStoreIntegrationTest {

    @Autowired ReconRunRepository runs;
    @Autowired ReconRecordRepository records;
    @Autowired DiscrepancyRepository discrepancies;
    @Autowired DiscrepancyDispositionRepository dispositions;
    @Autowired ReversalSuggestionRepository reversals;
    @Autowired ReconReportRepository reports;
    @Autowired AlertOutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final Instant T = Instant.parse("2026-08-17T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-18T23:59:59Z");

    @BeforeEach
    void cleanup() {
        for (String t : List.of("recon_record", "recon_record_reject", "discrepancy",
                "discrepancy_disposition", "reversal_suggestion", "discrepancy_action",
                "alert_outbox", "recon_report", "recon_run", "recon_run_seq")) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    // ---------- fixtures ----------

    private static String fp(char seed) {
        return String.valueOf(seed).repeat(64);
    }

    private ReconRun run(String runId, String scenario, String period, int seq, ReconRunStatus status, long rev) {
        return ReconRun.builder()
                .runId(runId)
                .key(RunKey.of(scenario, period, seq))
                .cutoffTime(NOW).matchWindowFrom(T).matchWindowTo(T1)
                .bucketCount(64).status(status).revision(rev)
                .createdAt(NOW).updatedAt(NOW)
                .build();
    }

    private ReconRecord record(String runId, String segmentId, Side side, int bucket, String key, long amount) {
        return ReconRecord.builder()
                .recordId(runId + ":" + segmentId + ":" + side + ":" + key)
                .runId(runId).segmentId(segmentId).side(side).sourceRole(SourceRole.MARKETING)
                .matchKey(MatchKey.of("k", key, bucket))
                .groupKey(GroupKey.of("k", key))
                .bucket(bucket)
                .money(Money.of("USD", amount))
                .entryType(EntryType.ISSUE)
                .bizTime(NOW)
                .rawRef("tbl:" + key)
                .build();
    }

    private Discrepancy disc(String runId, DiscrepancyType type, String fingerprint,
                             String matchKey, String groupKey, String currency, long expected, long actual) {
        return Discrepancy.builder()
                .discrepancyId("d:" + fingerprint.substring(0, 8)) // 保持 <= VARCHAR(64)
                .runId(runId).segmentId("SEG1")
                .type(type).fingerprint(fingerprint)
                .matchKey(matchKey).groupKey(groupKey).currency(currency)
                .expectedAmountMinor(expected).actualAmountMinor(actual)
                .deltaAmountMinor(expected - actual)
                .leftRawRef("L").rightRawRef("R")
                .build();
    }

    // ---------- ReconRun: claim 唯一键 + revision 乐观锁 ----------

    @Test
    void claimDuplicateUkRunConflicts() {
        runs.claim(run("run-A", "scn", "2026-08-17", 1, ReconRunStatus.CREATED, 0));
        // 不同 runId 但相同 (scenario, period, seq) -> uk_run 冲突
        assertThatThrownBy(() -> runs.claim(run("run-B", "scn", "2026-08-17", 1, ReconRunStatus.CREATED, 0)))
                .isInstanceOf(ConflictException.class);
        assertThat(runs.find("run-A")).isPresent();
        assertThat(runs.find("run-B")).isEmpty();
    }

    @Test
    void saveWithCurrentRevisionSucceedsAndBumps() {
        runs.claim(run("run-1", "scn", "2026-08-17", 1, ReconRunStatus.CREATED, 0));
        ReconRun loaded = runs.find("run-1").orElseThrow();
        assertThat(loaded.revision()).isZero();
        assertThat(loaded.status()).isEqualTo(ReconRunStatus.CREATED);

        runs.save(loaded.start(), 0); // CREATED -> LOADING, expected revision 0
        ReconRun afterFirst = runs.find("run-1").orElseThrow();
        assertThat(afterFirst.revision()).isEqualTo(1);
        assertThat(afterFirst.status()).isEqualTo(ReconRunStatus.LOADING);

        runs.save(afterFirst.toMatching(), 1);
        ReconRun afterSecond = runs.find("run-1").orElseThrow();
        assertThat(afterSecond.revision()).isEqualTo(2);
        assertThat(afterSecond.status()).isEqualTo(ReconRunStatus.MATCHING);
    }

    @Test
    void saveWithStaleRevisionConflicts() {
        runs.claim(run("run-2", "scn", "2026-08-17", 2, ReconRunStatus.CREATED, 0));
        runs.save(runs.find("run-2").orElseThrow().start(), 0); // now revision 1
        // 用旧 revision 0 再存 -> 冲突, 不改库
        assertThatThrownBy(() -> runs.save(run("run-2", "scn", "2026-08-17", 2, ReconRunStatus.FAILED, 0), 0))
                .isInstanceOf(ConflictException.class);
        assertThat(runs.find("run-2").orElseThrow().status()).isEqualTo(ReconRunStatus.LOADING);
        assertThat(runs.find("run-2").orElseThrow().revision()).isEqualTo(1);
    }

    // ---------- recon_record: batchInsert + cursor 升序 + 分批删除 ----------

    @Test
    void recordBatchInsertAndCursorAscending() {
        records.batchInsert(List.of(
                record("run-r", "SEG1", Side.LEFT, 0, "I-3", 300),
                record("run-r", "SEG1", Side.LEFT, 0, "I-1", 100),
                record("run-r", "SEG1", Side.LEFT, 0, "I-2", 200),
                record("run-r", "SEG1", Side.LEFT, 1, "I-9", 900),   // 其它桶
                record("run-r", "SEG1", Side.RIGHT, 0, "I-1", 100))); // 其它侧

        List<String> keys = new ArrayList<>();
        try (RecordCursor cursor = records.cursor("run-r", "SEG1", Side.LEFT, 0)) {
            ReconRecord r;
            while ((r = cursor.next()) != null) {
                keys.add(r.matchKey().value());
            }
        }
        assertThat(keys).containsExactly("I-1", "I-2", "I-3"); // 严格升序, 只当前桶/侧
    }

    @Test
    void deleteRecordsBoundedInBatchesAndScopedToRun() {
        List<ReconRecord> batch = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            batch.add(record("run-del", "SEG1", Side.LEFT, 0, "K" + i, i));
        }
        records.batchInsert(batch);
        records.batchInsert(List.of(
                record("run-keep", "SEG1", Side.LEFT, 0, "X1", 1),
                record("run-keep", "SEG1", Side.LEFT, 0, "X2", 2)));

        int d1 = records.deleteByRunBounded("run-del", 10);
        int d2 = records.deleteByRunBounded("run-del", 10);
        int d3 = records.deleteByRunBounded("run-del", 10);
        int d4 = records.deleteByRunBounded("run-del", 10);
        assertThat(d1).isEqualTo(10);
        assertThat(d2).isEqualTo(10);
        assertThat(d3).isEqualTo(5);
        assertThat(d4).isZero();
        assertThat(List.of(d1, d2, d3, d4)).allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(10));

        assertThat(count("recon_record", "run-del")).isZero();
        assertThat(count("recon_record", "run-keep")).isEqualTo(2); // 别的 Run 不受影响
    }

    // ---------- discrepancy: fingerprint 幂等 + 只删机器结果, 不碰人工痕迹 ----------

    @Test
    void upsertByFingerprintIsIdempotent() {
        Discrepancy d = disc("run-x", DiscrepancyType.AMOUNT_MISMATCH, fp('a'), "I-1", "I-1", "USD", 500, 400);
        discrepancies.upsertByFingerprint(d);
        discrepancies.upsertByFingerprint(d);
        discrepancies.upsertByFingerprint(disc("run-x", DiscrepancyType.AMOUNT_MISMATCH, fp('a'),
                "I-1", "I-1", "USD", 500, 300)); // 同 fingerprint 覆盖
        List<Discrepancy> all = discrepancies.listByRun("run-x");
        assertThat(all).hasSize(1);
        assertThat(all.get(0).actualAmountMinor()).isEqualTo(300); // 值被覆盖为最后一次
    }

    @Test
    void upsertIsIdempotentForEmptyKeyTypes() {
        // BRIDGE_BROKEN: 空 match_key/group_key, 但 fingerprint 非空
        discrepancies.upsertByFingerprint(
                disc("run-e", DiscrepancyType.BRIDGE_BROKEN, fp('b'), null, null, "USD", 700, 0));
        discrepancies.upsertByFingerprint(
                disc("run-e", DiscrepancyType.BRIDGE_BROKEN, fp('b'), null, null, "USD", 700, 0));
        // CURRENCY_MISMATCH: 空 currency
        discrepancies.upsertByFingerprint(
                disc("run-e", DiscrepancyType.CURRENCY_MISMATCH, fp('c'), null, null, null, 800, 900));
        discrepancies.upsertByFingerprint(
                disc("run-e", DiscrepancyType.CURRENCY_MISMATCH, fp('c'), null, null, null, 800, 900));

        List<Discrepancy> all = discrepancies.listByRun("run-e");
        assertThat(all).hasSize(2); // 两条各自幂等, 无重复
        assertThat(all).allSatisfy(x -> assertThat(x.fingerprint()).isNotBlank().hasSize(64));
    }

    @Test
    void deleteMachineDiscrepanciesOnlyKeepsHumanArtifacts() {
        // 5 条机器差异 (machine_result=1)
        for (int i = 0; i < 5; i++) {
            discrepancies.upsertByFingerprint(
                    disc("run-m", DiscrepancyType.AMOUNT_MISMATCH, fp((char) ('a' + i)), "I" + i, "I" + i, "USD", 10, 5));
        }
        // 1 条 machine_result=0 (非机器结果) 直插, 应豁免删除
        jdbc.update("""
                INSERT INTO discrepancy(discrepancy_id, run_id, segment_id, type, fingerprint,
                    expected_amount_minor, actual_amount_minor, delta_amount_minor, machine_result,
                    created_at, updated_at)
                VALUES ('manual-1','run-m','SEG1','AMOUNT_MISMATCH', ?, 0,0,0, 0, ?, ?)
                """, fp('z'), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));

        // 人工痕迹: 处置 + 冲正建议 (永不被删)
        dispositions.upsert(DiscrepancyDisposition.builder()
                .id("disp-1").fingerprint(fp('a')).scenarioCode("scn").accountingPeriod("2026-08-17")
                .segmentId("SEG1").status(DispositionStatus.RESOLVED).operator("ops").version(0).build());
        reversals.insertIfAbsent(ReversalSuggestion.builder()
                .id("rev-1").fingerprint(fp('a')).runId("run-m").suggestedAmountMinor(10).currency("USD")
                .status(ReversalStatus.SUGGESTED).idempotencyKey("idem-1").build());

        int b1 = discrepancies.deleteOpenMachineByRunBounded("run-m", 2);
        int b2 = discrepancies.deleteOpenMachineByRunBounded("run-m", 2);
        int b3 = discrepancies.deleteOpenMachineByRunBounded("run-m", 2);
        int b4 = discrepancies.deleteOpenMachineByRunBounded("run-m", 2);
        assertThat(List.of(b1, b2, b3, b4)).allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(2));
        assertThat(b1 + b2 + b3 + b4).isEqualTo(5); // 恰好删掉 5 条机器结果

        // machine_result=0 的一条幸存
        List<Discrepancy> left = discrepancies.listByRun("run-m");
        assertThat(left).extracting(Discrepancy::discrepancyId).containsExactly("manual-1");
        // 人工处置 / 冲正建议 完好无损
        assertThat(dispositions.findByFingerprint(fp('a'))).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reversal_suggestion WHERE run_id='run-m'",
                Integer.class)).isEqualTo(1);
    }

    // ---------- 幂等插入: reversal / alert_outbox ----------

    @Test
    void reversalInsertIfAbsentIsIdempotent() {
        ReversalSuggestion s = ReversalSuggestion.builder()
                .id("r1").fingerprint(fp('a')).runId("run-r").suggestedAmountMinor(1234).currency("USD")
                .status(ReversalStatus.SUGGESTED).idempotencyKey("rev-idem").build();
        assertThat(reversals.insertIfAbsent(s)).isTrue();
        // 相同 idempotency_key (即使 id 不同) -> 幂等命中, 不重复
        ReversalSuggestion dup = ReversalSuggestion.builder()
                .id("r2").fingerprint(fp('a')).runId("run-r").suggestedAmountMinor(9999).currency("USD")
                .status(ReversalStatus.SUGGESTED).idempotencyKey("rev-idem").build();
        assertThat(reversals.insertIfAbsent(dup)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reversal_suggestion", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT suggested_amount_minor FROM reversal_suggestion", Long.class))
                .isEqualTo(1234L); // 第一次的值, 未被覆盖
    }

    @Test
    void alertOutboxInsertIfAbsentAndRelayLifecycle() {
        AlertOutbox a = AlertOutbox.builder()
                .id("a1").runId("run-a").fingerprint(fp('a')).payload("{\"msg\":1}")
                .status(AlertStatus.PENDING).attempt(0).idempotencyKey("alert-idem").build();
        assertThat(outbox.insertIfAbsent(a)).isTrue();
        assertThat(outbox.insertIfAbsent(AlertOutbox.builder()
                .id("a2").runId("run-a").fingerprint(fp('a')).payload("dup")
                .status(AlertStatus.PENDING).idempotencyKey("alert-idem").build())).isFalse();

        assertThat(outbox.listPending()).extracting(AlertOutbox::id).containsExactly("a1");
        outbox.markSent("a1", NOW);
        assertThat(outbox.listPending()).isEmpty();

        // FAILED 中继: attempt 递增
        outbox.insertIfAbsent(AlertOutbox.builder()
                .id("a3").runId("run-a").fingerprint(fp('b')).payload("p")
                .status(AlertStatus.PENDING).idempotencyKey("alert-idem-2").build());
        outbox.markFailed("a3");
        assertThat(jdbc.queryForObject("SELECT status FROM alert_outbox WHERE id='a3'", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt FROM alert_outbox WHERE id='a3'", Integer.class))
                .isEqualTo(1);
    }

    // ---------- recon_report saveAll + listByRun 往返 + 幂等覆盖 ----------

    @Test
    void reportSaveAllAndListByRunRoundTrip() {
        ReconReport usd = ReconReport.builder()
                .runId("run-rep").segmentId("SEG1").currency("USD")
                .expectedTotalMinor(1000).matchedAmountMinor(900).amountMismatchMinor(100)
                .rightSideTotalMinor(1000).leftResidualMinor(0).rightResidualMinor(0).balanced(true).build();
        ReconReport eur = ReconReport.builder()
                .runId("run-rep").segmentId("SEG1").currency("EUR")
                .expectedTotalMinor(500).matchedAmountMinor(500)
                .rightSideTotalMinor(500).leftResidualMinor(0).rightResidualMinor(0).balanced(true).build();
        reports.saveAll(List.of(usd, eur));

        List<ReconReport> loaded = reports.listByRun("run-rep");
        assertThat(loaded).hasSize(2);
        assertThat(loaded).extracting(ReconReport::currency).containsExactly("EUR", "USD"); // ORDER BY currency
        assertThat(loaded).filteredOn(r -> r.currency().equals("USD")).singleElement()
                .satisfies(r -> {
                    assertThat(r.expectedTotalMinor()).isEqualTo(1000);
                    assertThat(r.matchedAmountMinor()).isEqualTo(900);
                    assertThat(r.amountMismatchMinor()).isEqualTo(100);
                    assertThat(r.balanced()).isTrue();
                });

        // 同 uk_report 覆盖 (幂等 upsert), 行数不增, 值更新
        reports.saveAll(List.of(ReconReport.builder()
                .runId("run-rep").segmentId("SEG1").currency("USD")
                .expectedTotalMinor(1000).matchedAmountMinor(1000)
                .rightSideTotalMinor(1000).balanced(true).build()));
        List<ReconReport> after = reports.listByRun("run-rep");
        assertThat(after).hasSize(2);
        assertThat(after).filteredOn(r -> r.currency().equals("USD")).singleElement()
                .satisfies(r -> assertThat(r.matchedAmountMinor()).isEqualTo(1000));
    }

    // ---------- disposition: version 乐观锁 ----------

    @Test
    void dispositionUpsertOptimisticLock() {
        dispositions.upsert(disp(DispositionStatus.RESOLVED, 0)); // insert, version 0
        assertThat(dispositions.findByFingerprint(fp('a')).orElseThrow().version()).isZero();

        // 用 expected version 0 更新 -> version 1
        dispositions.upsert(disp(DispositionStatus.CLOSED, 0));
        assertThat(dispositions.findByFingerprint(fp('a')).orElseThrow().status()).isEqualTo(DispositionStatus.CLOSED);
        assertThat(dispositions.findByFingerprint(fp('a')).orElseThrow().version()).isEqualTo(1);

        // 旧 expected version 0 (stale) -> 冲突, 不改库
        assertThatThrownBy(() -> dispositions.upsert(disp(DispositionStatus.REOPENED, 0)))
                .isInstanceOf(ConflictException.class);
        assertThat(dispositions.findByFingerprint(fp('a')).orElseThrow().status()).isEqualTo(DispositionStatus.CLOSED);

        // 正确 expected version 1 -> version 2
        dispositions.upsert(disp(DispositionStatus.REOPENED, 1));
        assertThat(dispositions.findByFingerprint(fp('a')).orElseThrow().version()).isEqualTo(2);
    }

    private DiscrepancyDisposition disp(DispositionStatus status, int expectedVersion) {
        return DiscrepancyDisposition.builder()
                .id("disp-x").fingerprint(fp('a')).scenarioCode("scn").accountingPeriod("2026-08-17")
                .segmentId("SEG1").status(status).operator("ops").version(expectedVersion).build();
    }

    private int count(String table, String runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id = ?", Integer.class, runId);
    }
}
